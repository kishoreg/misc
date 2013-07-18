#!/usr/bin/perl -w
#
# A tool to generate various invocations of other tools
# author: Greg Brandt (gbrandt@linkedin.com)
#
use strict;
use Algorithm::Combinatorics qw( combinations );
use Getopt::Long;
use Data::Dumper;
use Carp;
use JSON;

my $exec;             # The command to execute
my $params_spec_json; # A JSON object describing the parameters for that command
my $params_file;      # A file containing params JSON instead
my $dry_run;          # If true, actually execute commands
my $prompt;           # If true, prompt before executing next command

GetOptions(
  "exec=s"   => \$exec,
  "params=s" => \$params_spec_json,
  "paramsFile=s" => \$params_file,
  "dryRun"   => \$dry_run,
  "prompt"   => \$prompt,
);

die "Can only provide either --params or --paramsFile"
  if $params && $params_file;

# Parse the param specification
if ($params_file)
{
  open my $f, "<$params_file";
  $params_spec_json = do { local($/); <$f> };
  close $f;
}
my $params_spec = from_json($params_spec_json);

# Extract all of the params
my @params = keys %{ $params_spec };

my $i = 0;

# For each of the possible k's in nCk
for my $k (0 .. scalar(@params))
{
  # Compute the nCk different combinations of parameters
  for my $combination (combinations(\@params, $k))
  {
    # Build invocation for this particular combination
    my %invocation;
    for my $param (@{ $combination })
    {
      # A value was specified
      if ($params_spec->{$param}->{'value'})
      {
        $invocation{$param} = $params_spec->{$param}->{'value'};
      }
      # Or we should randomly generate an appropriate one
      elsif ($params_spec->{$param}->{'type'})
      {
        my $type = $params_spec->{$param}->{'type'};
        if ($type eq 'INT')
        {
          $invocation{$param} = int(rand(100000));
        }
        elsif ($type eq 'STRING')
        {
          my $rand_str = "";
          $rand_str .= chr(int(rand(26)) + 65) for (1 .. 30);
          $invocation{$param} = $rand_str;
        }
        elsif ($type eq 'BOOLEAN')
        {
          $invocation{$param} = ""; # Just a flag
        }
        else
        {
          croak "Unsupported type $type for $param";
        }
      }
      else
      {
        croak "Couldn't find value or type for $param";
      }
    }

    # Build command
    my $cmd = "$exec ";
    while (my ($k, $v) = each %invocation)
    {
      $cmd .= "$k $v ";
    }

    # Log invocation
    print "INVOCATION_", $i++, "\@", scalar(time), " => ", $cmd, "\n";

    # Run and output if not a dry run
    if (!$dry_run)
    {
      open my $cmd_fh, "$cmd |";
      while (<$cmd_fh>)
      {
        print "$_";
      }
      close $cmd_fh;
    }

    # If prompt specify, wait for user input to continue
    if ($prompt)
    {
      print "Hit ENTER to continue";
      my $dummy = <STDIN>;
    }
  }
}
