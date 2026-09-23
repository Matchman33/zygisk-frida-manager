#pragma once
#include <string>
#include <vector>

namespace zfm {
struct ElfInfo {
    std::string abi;
    std::vector<std::string> needed;
};
ElfInfo inspect_elf(const std::string& path);
void patch_elf(const std::string& input, const std::string& output, const std::string& dependency);
}
