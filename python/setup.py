#!/usr/bin/python

try:
  from setuptools import setup
except ImportError:
  from distutils.core import setup

config = {
  'description': 'Misc Python tools',
  'author': 'Greg Brandt',
  'url': '...',
  'download_url': '...',
  'author_email': 'brandt.greg@gmail.com',
  'version': '0.0.1',
  'install_requires': ['nose'],
  'packages': ['gavro'],
  'scripts': [],
  'name': 'gtools'
}

setup(**config)
