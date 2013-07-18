#!/usr/bin/perl -w
use strict;
use Algorithm::Combinatorics qw( combinations );
use Getopt::Long;
use JSON;

my $exec;
my $params_spec_json;

GetOptions(
  "exec=s" => \$exec,
  "params=s" => \$params_spec_json,
);

# Parse the param specification
my $params_spec = from_json($params_spec_json);

# Extract all of the params
my @params = keys %{ $params_spec };

# Test every possible combination of args w/ random input
for my $k (1 .. scalar(@params))
{
  my @combinations = combinations(\@params, $k);
}

#use Data::Dumper;
#print Dumper($params);
#print Dumper(\@combinations);
