#!/usr/bin/perl -w
#
# A tool to generate various invocations of other tools
#
use strict;
use Algorithm::Combinatorics qw( combinations );
use Carp;
use Getopt::Long;
use JSON;
use Pod::Usage;

my $exec;             # The command to execute
my $params_spec_json; # A JSON object describing the parameters for that command
my $params_file;      # A file containing params JSON instead
my $dry_run;          # If true, actually execute commands
my $prompt;           # If true, prompt before executing next command
my $help;             # If true, print a help message and exit

GetOptions(
  "exec=s"       => \$exec,
  "params=s"     => \$params_spec_json,
  "paramsFile=s" => \$params_file,
  "dryRun"       => \$dry_run,
  "prompt"       => \$prompt,
  "help"         => \$help,
);

# Validate
pod2usage(1)
  if $help;
pod2usage(-msg => "--exec required", -exitval => 2)
  if !$exec;
pod2usage(-msg => "--params or --paramsFile required", -exitval => 3)
  if !$params_spec_json && !$params_file;
pod2usage(-msg => "Can only provide either --params or --paramsFile", -exitval => 4)
  if $params_spec_json && $params_file;

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

# For each of the possible k's in nCk
my $i = 0;
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
          $rand_str .= chr(int(rand(ord('z') - ord('a'))) + ord('a')) for (1 .. 30);
          $invocation{$param} = $rand_str;  # n.b. $rand_str =~ /[a-z]{30}/
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
      <STDIN>;
    }
  }
}

=head1 NAME

tool_tester.pl - Utility to test different invocations of a tool

=head1 SYNOPSIS

  ./tool_tester.pl --exec ./my_other_tool --params '{"--opt1": {"value": "hello"}, "--opt2": {"type": "STRING"}'

  ./tool_tester.pl --exec 'java -jar someVerboselyNamedImplPkg-SNAPSHOT.jar' --paramsFile /path/to/params.json

=head1 DESCRIPTION

This tool effectively shoots you in the foot in many different ways, with the
hope that you'll be able to recover.

=head1 OPTIONS

=over 4

=item B<--exec>

The command to execute

=item B<--params>

JSON-encoded configuration object (see: perldoc tool_tester.pl)

=item B<--paramsFile>

File path to JSON-encoded configuration object (see: perldoc tool_tester.pl)

=item B<--dryRun>

Do not actually perform the generated commands

=item B<--prompt>

Wait for user input to execute the next command

=item B<--help>

Print this help message

=back

=head1 CONFIGURATION

A JSON-encoded configuration file (via "--params" or "--paramsFile") must be supplied. E.g.:

  {
    "--zkAddr": {
      "value": "localhost:2181"
    },
    "--clusterName": {
      "value": "ESPRESSO_CLUSTER"
    },
    "--restore": {
      "type": "BOOLEAN"
    },
    "--catchup": {
      "type": "BOOLEAN"
    },
    "--dbPartitions": {
      "value": "{\"mydb\":[0,1]}"
    },
    "--instanceName": {
      "value": "localhost_12930"
    }
  }

Keys in this object correspond to the options for your tool. 

Values in this object correspond to what arguments should be supplied to those
options. 

If the value object contains a key "value", then that specific string will be
used as the argument. If it contains a key "type", then an appropriate random
value will be generated.

Allowed "type"s are ["INT", "STRING", "BOOLEAN"].

=head1 DEPENDENCIES

=over 4

=item Algorithm::Combinatorics

=item JSON

=back

=head1 AUTHOR

Greg Brandt (brandt.greg@gmail.com)

=cut
