#!/usr/bin/python
"""

Validates a collection of Avro record schemas for backwards- and
forwards-compatibility.

Allowed / Disallowed Transformations:

  OK:
    * Record:
      - add field with default value
      - remove field with default value
      - add alias to a field
      - remove alias of a field
      - change alias of a field
      - re-order fields
      - change default value for a field
    * Enum:
      - re-order symbols
    * Union:
      - re-order types (allows for change of default value type)

  ERROR:
    * Record:
      - add field without default value
      - remove field without default value
      - change type of field
    * Enum:
      - add symbol
      - remove symbol
    * Union:
      - add type
      - remove type
    * Array:
      - change items type
    * Map:
      - change values type
    * Fixed:
      - change name
      - change size

Default Values and Aliases:

  To obtain the most flexibility in Avro schema evolution, one should always
  add a default value for each field, and refer to fields by aliases from the
  application level.

  The reason for this is that the presence of a default field allows the field
  to eventually be removed (as will often be the case in rapid application
  development when fields are deprecated). The presence of an alias allows the
  field's type to change entirely as seen from the application's perspective.

  For example, consider the following schema that represents a date:

  {
    "name": "MyDate",
    "type": "record",
    "fields": [
      {
        "name": "date_string",
        "aliases": ["date"],
        "type": "string",
        "default": "1/1/1970"
      }
    ]
  }

  Now we decide that it's better to encode them as longs, so we remove
  "date_string" (which is valid since it has a default value), and add
  "date_long":

  {
    "name": "MyDate",
    "type": "record",
    "fields": [
      {
        "name": "date_long",
        "aliases": ["date"],
        "type": "long",
        "default": 0
      }
    ]
  }

  Although previous records aren't really semantically meaningful (all will
  acquire the default value of 0), the application can go right along referring
  to this field as "date", and no messy maintenance needs to be performed.

Theoretical Discussion:

  Observation: A set S of compatible schemas is a monoid under the Union binary
  operator.

    CLOSURE
      If A and B are in S, then A U B is in S. Proof: If A and B are
      compatible, then it is the case that the set of fields in (A-B) and (B-A)
      do not affect up- and down-conversion. Therefore, the presence of those
        fields in A U B doesn't affect up- and down-conversion.
    ASSOCIATIVITY
      For all A, B, C in S, (A U B) U C = A U (B U C). Above argument kind of
      applies.
    IDENTITY
      The empty schema is the identity element. E.g.
      { "name": "Empty", "type": "record", "fields":[]}

  Now consider a function f: X -> Y, where X and Y are monoids as described
  above. We now have a mathematical category (call it AVRO) whose objects are
  those monoids and whose morphisms are those functions.

Validation Algorithm:

  We can leverage the closure property of a monoid of compatible schemas in the
  following way:

  Given schemas S[1] .. S[N], we want to validate whether S[i] is backwards and
  forwards compatible with S[j] for all i,j in (1..N)

  To do this, we repeatedly check compatibility with each schema against a
  schema which contains the superset of all fields (SS) seen thus far. SS is
  compatible under the closure property.

  SS = S[1]  # initially the first schema, which by definition is compatible with itself
  for i in (2..N):
    check(SS, S[i]) # throws
    SS = combine(SS, S[i])

Dealing with Forwards- and Backwards-compatibility:

  Sometimes we want to evolve a schema in a non-compatible way. We might
  introduce a forwards-incompatibility (i.e. readers can't read newer records),
  or a backwards-incompatibility (i.e. readers can't read older records).

  We should always be able to prevent forwards-incompatibility after having
  read the above section because we (to some extent) can control the future.
  However, backwards-incompatibility remains to be dealt with.

  For example, a field may exist with no default value. This means we'll have
  to live with it forever. This is okay as long as everything in a set of
  schemas has that field, but we cannot ever remove it unless we add default
  values to all schemas.

  To overcome this, we can apply some category theory: if we can find a set of
  morphisms such that our category is a fully-connected graph, we can guarantee
  we can read any record written with any schema.

  Moreover, we can also bound the number of data transformations needed to
  covert a record to O(log n). To do this, we choose morphisms such that we
  construct a Chord overlay network among compatible sets of schemas
  (http://en.wikipedia.org/wiki/Chord_%28peer-to-peer%29).

  Realistically though, schemas are evolved sequentially in time. So if we
  consider a linear topology of monoids and just create a morphism to move from
  an older monoid to a newer monoid, we simplify things a lot. This requires
  O(n) transformations, but hopefully n is small if we're constructing our
  schemas right. It's also likely that the number of transformations we need
  converges to zero as the system settles into the new schema and old records
  get lazily upconverted via new writes.

author: Greg Brandt (gbrandt@linkedin.com)

"""
from avro import schema
import json
import string

def check(schemas):
  """

  Checks a collection of schemas for forwards- and backwards- compatibility

  """
  ss = schemas[0]
  for i in xrange(1, len(schemas)):
    _check_record(ss, schemas[i], [])
    ss = _combine_record(ss, schemas[i])

# Copies A, then superimposes B on the copy
def _combine_record(A, B):
  SS = schema.parse(json.dumps(A.to_json())) # there must be a better way...
  a_field_names = map(lambda f: f.name, A.fields)
  b_field_names = map(lambda f: f.name, B.fields)
  for b_field_name in b_field_names:
    if not b_field_name in a_field_names:
      SS.fields.append(B.fields_dict[b_field_name])
  return SS

# Outputs the field name trace in dot format
def _dump_trace(trace):
  return string.join(trace, '.')

# Throws exception if A and B are of unexpected Python schema classes
def _check_type(A, B, schema_type, trace):
  if not type(A) is schema_type:
    raise Exception("%s %s is not a %s" % (_dump_trace(trace), A, schema_type))
  if not type(B) is schema_type:
    raise Exception("%s %s is not a %s" % (_dump_trace(trace), B, schema_type))

# Returns the appropriate check function for a given Python schema class
def _class_to_function(schema_class):
  if schema_class == schema.RecordSchema:
    return _check_record
  elif schema_class == schema.EnumSchema:
    return _check_enum
  elif schema_class == schema.UnionSchema:
    return _check_union
  elif schema_class == schema.ArraySchema:
    return _check_array
  elif schema_class == schema.MapSchema:
    return _check_map
  elif schema_class == schema.FixedSchema:
    return _check_fixed
  elif schema_class == schema.PrimitiveSchema:
    return _check_primitive
  else:
    raise Exception("Unknown schema class %s" % schema_class)

# Returns a fixed ordinal value for a given schema type (for sorting)
def _type_to_ordinal(schema_type):
  types = [
      "record",
      "enum",
      "array",
      "map",
      "union",
      "fixed",
      "null",
      "boolean",
      "int",
      "long",
      "float",
      "double",
      "bytes",
      "string"
  ]
  return types.index(schema_type)

# Recursively check a record for validity
# n.b. order of fields doesn't matter
def _check_record(A, B, trace):
  _check_type(A, B, schema.RecordSchema, trace)
  trace.append(A.name)

  f = lambda x: x.name
  a_field_names = map(f, A.fields)
  b_field_names = map(f, B.fields)

  for a_field in A.fields:

    # case 1: in A, not in B
    #   this is okay as long as A has a default value for the field
    if not a_field.name in b_field_names:
      if not a_field.has_default:
        raise Exception("%s.%s must have default value" 
            % (_dump_trace(trace), a_field.name))

    # case 2: in A, in B
    #   recursively check fields (go with A's type)
    else:
      f = _class_to_function(type(a_field.type))
      b_field = B.fields_dict[a_field.name]
      trace.append(a_field.name)
      f(a_field.type, b_field.type, trace)
      trace.pop()

  for b_field in B.fields:

    # case 3: in B, not in A
    #   this is okay as long as B has a default value for the field
    if not b_field.name in a_field_names:
      if not b_field.has_default:
        raise Exception("%s.%s must have default value" 
            % (_dump_trace(trace), b_field.name))

  trace.pop()

# Checks if symbols haven't been added / removed
def _check_enum(A, B, trace):
  _check_type(A, B, schema.EnumSchema, trace)

  a_symbols = sorted(A.symbols)
  b_symbols = sorted(B.symbols)

  if len(a_symbols) != len(b_symbols):
    raise Exception("%s symbols error A=%s, B=%s" 
        % (_dump_trace(trace), str(a_symbols), str(b_symbols)))

  for i in xrange(0,len(a_symbols)):
    if a_symbols[i] != b_symbols[i]:
      raise Exception("%s symbols error A=%s, B=%s" 
          % (_dump_trace(trace), str(a_symbols), str(b_symbols)))

# n.b. a union must contain all the same types, but they may be reordered
def _check_union(A, B, trace):
  _check_type(A, B, schema.UnionSchema, trace)

  if len(A.schemas) != len(B.schemas):
    f = lambda x: x.type
    a_types = map(f, A.schemas)
    b_types = map(f, B.schemas)
    raise Exception("%s union error A=%s, B=%s" 
        % (_dump_trace(trace), str(a_types), str(b_types)))

  f = lambda x: _type_to_ordinal(x.type)
  a_schemas = sorted(A.schemas, key=f)
  b_schemas = sorted(B.schemas, key=f)

  for i in xrange(0,len(a_schemas)):
    f = _class_to_function(type(a_schemas[i]))
    f(a_schemas[i], b_schemas[i], trace)

# Checks the item type of arrays
def _check_array(A, B, trace):
  _check_type(A, B, schema.ArraySchema, trace)
  f = _class_to_function(type(A.items))
  f(A.items, B.items, trace)

# Checks the value type of maps
def _check_map(A, B, trace):
  _check_type(A, B, schema.MapSchema, trace)
  f = _class_to_function(type(A.values))
  f(A.values, B.values, trace)

# Checks the name and size of fixed
def _check_fixed(A, B, trace):
  _check_type(A, B, schema.FixedSchema, trace)

  if A.size != B.size:
    raise Exception("%s size error A=%d, B=%d" 
        % (_dump_trace(trace), A.size, B.size))

  if A.name != B.name:
    raise Exception("%s name error A=%s, B=%s" 
        % (_dump_trace(trace), A.name, B.name))

# Checks primitives (equality is sufficient)
def _check_primitive(A, B, trace):
  _check_type(A, B, schema.PrimitiveSchema, trace)

  if A.type != B.type:
    raise Exception("%s type error A=%s, B=%s" 
        % (_dump_trace(trace), A, B))

# Main
if __name__ == '__main__':
  import sys
  schemas = map(lambda x: schema.parse(open(x).read()), sys.argv[1:])
  check(schemas)
