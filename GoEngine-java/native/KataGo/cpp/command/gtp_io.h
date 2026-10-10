#pragma once
#include <istream>
#include <ostream>
#include <string>
#include <vector>

// GTP's existing CLI entry point continues using stdin/stdout.
// JNI sessions provide distinct streams, never redirecting global std::cin/cout.
namespace MainCmds {
  int gtpWithIO(const std::vector<std::string>& args, std::istream& input, std::ostream& output);
}
