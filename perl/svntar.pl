#!/usr/bin/perl
# 
# When executed from a subversion repository, given a changelist name, creates a
# tgz archive of the changelist. Kind of like git stash
#
# @author Greg Brandt <gbrandt@linkedin.com>
#
use strict;
use Getopt::Long;

# Get args
my $cl_name;
my $out_file;
GetOptions(
        "cl=s" => \$cl_name,
        "o=s" => \$out_file,
);

# Validate args
die "usage: $0 -cl <cl-name> -o <out-file>" if !$cl_name || !$out_file;

# Issue svn status command
my $output = `svn st`;
my @lines = split "\n", $output;

# Read lines until we find the first file
my $i = 0;
while ($i++ < scalar(@lines)) {
        if ($lines[$i] =~ /Changelist '$cl_name'/) {
                $i++;   # Move to first file
                last;   # And done 
        }
}

# Check if we found it
if ($i > scalar(@lines)) {
        die "no changelist $cl_name!\n";
}

# Collect all the filenames
my @files;
while ($i < scalar(@lines) && $lines[$i] !~ /^\s*$/) {
        my $f = $lines[$i++];
        $f =~ s/[A-Z]\s* //;
        push @files, $f;
}

# Tar them up
my $f_str = join ' ', @files;
print `tar cvzf $out_file $f_str`;
