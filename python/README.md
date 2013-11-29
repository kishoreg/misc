Synopsis
========

A collection of random code bits.

Execute `nosetests` from this directory to make sure I didn't do anything wrong.

Avro validator
--------------

This tool validates forwards- and backwards-compatibility of Avro schemas.

```
sudo python setup.py install
python -m tools.avro.validator schema.1.avsc schema.2.avsc ...
```
