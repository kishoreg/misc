"""

Tests allowed and disallowed schema evolution operations. See the schemas in
BASE_DIR.

author: Greg Brandt (brandt.greg@gmail.com)

"""
from avro import schema
from avro.io import DatumReader, DatumWriter, BinaryEncoder, BinaryDecoder
from gavro import validator
import StringIO

BASE_DIR = './tests/schemas'

def test_remove_field_no_default_value():
  _check('base', 'removeNoDefault')

def test_add_field_no_default_value():
  _check('base', 'addNoDefault')

def test_remove_union_type():
  _check('base', 'removeUnionType')

def test_add_union_type():
  _check('base', 'addUnionType')

def test_remove_union_type():
  _check('base', 'removeUnionType')

def test_add_enum_symbol():
  _check('base', 'addEnumSymbol')

def test_remove_enum_symbol():
  _check('base', 'removeEnumSymbol')

def test_change_field_type():
  _check('base', 'changeFieldType')

def test_change_array_items():
  _check('base', 'changeArrayItems')

def test_change_map_values():
  _check('base', 'changeMapValues')

def test_change_fixed_name():
  _check('base', 'changeFixedName')
    
def test_change_fixed_size():
  _check('base', 'changeFixedSize')

def test_allowed_operations():
  fst = schema.parse(open("%s/MyRecord.base.avsc" % BASE_DIR).read())
  sec = schema.parse(open("%s/MyRecord.good.avsc" % BASE_DIR).read())
  validator.check([fst, sec])

def test_sanity():
  """

  Ensures that our "base" and "good" schemas are actually forwards- and
  backwards-compatible

  """
  # fst schema / record
  fst_schema = schema.parse(open("%s/MyRecord.base.avsc" % BASE_DIR).read())
  fst_writer = DatumWriter(writers_schema=fst_schema)
  fst_record = {
      "fieldWithoutDefaultValue": 0,
      "properField": 0,
      "enumField": "A",
      "unionField": None,
      "arrayField": ["world"],
      "mapField": {"hello": "world"},
      "fixedField": "aaaaaaaaaaaaaaaa"
  }

  # sec schema / record
  sec_schema = schema.parse(open("%s/MyRecord.good.avsc" % BASE_DIR).read())
  sec_writer = DatumWriter(writers_schema=sec_schema)
  sec_record = {
      "fieldWithoutDefaultValue": 0,
      "properField2": 0,
      "enumField": "B",
      "unionField": None,
      "arrayField": ["world"],
      "fixedField": "bbbbbbbbbbbbbbbb"
  }

  # Encode record w/ fst
  fst_buf = StringIO.StringIO()
  fst_encoder = BinaryEncoder(fst_buf)
  fst_writer.write(fst_record, fst_encoder)
  fst_data = fst_buf.getvalue()

  # Encode record w/ sec
  sec_buf = StringIO.StringIO()
  sec_encoder = BinaryEncoder(sec_buf)
  sec_writer.write(sec_record, sec_encoder)
  sec_data = sec_buf.getvalue()

  # writers == fst, readers == sec
  sec_reader = DatumReader(writers_schema=fst_schema, readers_schema=sec_schema)
  sec_decoder = BinaryDecoder(StringIO.StringIO(fst_data))
  sec_from_fst = sec_reader.read(sec_decoder) # no exception -> good

  # writers == sec, readers == fst
  fst_reader = DatumReader(writers_schema=sec_schema, readers_schema=fst_schema)
  fst_decoder = BinaryDecoder(StringIO.StringIO(sec_data))
  fst_from_sec = fst_reader.read(fst_decoder) # no exception -> good

def _check(fst_name, sec_name):
  """

  Tests evolution from schema named MyRecord.{fst_name}.avsc to schema named
  MyRecord.{sec_name}.avsc in BASE_DIR

  """
  fst = schema.parse(open("%s/MyRecord.%s.avsc" % (BASE_DIR, fst_name)).read())
  sec = schema.parse(open("%s/MyRecord.%s.avsc" % (BASE_DIR, sec_name)).read())
  try:
    validator.check([fst, sec])
  except:
    "good"
