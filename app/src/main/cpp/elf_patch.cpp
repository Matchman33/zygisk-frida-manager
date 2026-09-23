#include "elf_patch.hpp"
#include <LIEF/ELF.hpp>
#include <algorithm>
#include <fstream>
#include <map>
#include <stdexcept>

namespace zfm {
namespace {
using namespace LIEF::ELF;

std::unique_ptr<Binary> parse(const std::string& path, uint64_t page_size = 0) {
    std::ifstream source(path, std::ios::binary | std::ios::ate);
    if (!source || source.tellg() < 0) throw std::runtime_error("Cannot read ELF file");
    const uint64_t file_size = static_cast<uint64_t>(source.tellg());
    source.close();
    ParserConfig config;
    config.page_size = page_size;
    auto binary = Parser::parse(path, config);
    if (!binary) throw std::runtime_error("Invalid or unsupported ELF file");
    if (binary->header().file_type() != Header::FILE_TYPE::DYN || binary->has_interpreter())
        throw std::runtime_error("Select a shared library, not an executable or object file");
    if (binary->header().identity_data() != Header::ELF_DATA::LSB)
        throw std::runtime_error("Only little-endian Android ELF files are supported");
    if (!binary->has(DynamicEntry::TAG::STRTAB))
        throw std::runtime_error("Missing dynamic string table");
    bool loaded = false;
    for (const Segment& segment : binary->segments()) {
        if (segment.type() != Segment::TYPE::LOAD) continue;
        loaded = true;
        const auto alignment = segment.alignment();
        if (segment.file_offset() > file_size || segment.physical_size() > file_size - segment.file_offset()
                || segment.virtual_size() < segment.physical_size())
            throw std::runtime_error("Load segment exceeds the ELF file bounds");
        if (alignment > 0x200000 || (alignment > 1 && (alignment & (alignment - 1)) != 0))
            throw std::runtime_error("Unsupported load segment alignment");
        if (alignment > 1 && (segment.virtual_address() % alignment != segment.file_offset() % alignment))
            throw std::runtime_error("Invalid load segment alignment");
    }
    if (!loaded) throw std::runtime_error("Missing load segments");
    return binary;
}

ElfInfo describe(const Binary& binary) {
    ElfInfo info;
    const auto& header = binary.header();
    switch (header.machine_type()) {
        case ARCH::AARCH64: info.abi = "arm64-v8a"; break;
        case ARCH::ARM: info.abi = "armeabi-v7a"; break;
        case ARCH::X86_64: info.abi = "x86_64"; break;
        case ARCH::I386: info.abi = "x86"; break;
        default: throw std::runtime_error("Unsupported Android architecture");
    }
    bool is64 = info.abi == "arm64-v8a" || info.abi == "x86_64";
    if ((header.identity_class() == Header::CLASS::ELF64) != is64)
        throw std::runtime_error("ELF class does not match the machine architecture");
    for (const DynamicEntry& entry : binary.dynamic_entries()) {
        if (entry.tag() == DynamicEntry::TAG::NEEDED) {
            const auto& name = static_cast<const DynamicEntryLibrary&>(entry).name();
            if (name.empty() || name.size() > 1024 || info.needed.size() >= 1024)
                throw std::runtime_error("Invalid or excessive ELF dependencies");
            info.needed.push_back(name);
        }
    }
    return info;
}
}

ElfInfo inspect_elf(const std::string& path) { return describe(*parse(path)); }

void patch_elf(const std::string& input, const std::string& output, const std::string& dependency) {
    if (input == output) throw std::runtime_error("Input and output must differ");
    if (dependency.empty() || dependency.size() > 128 ||
        dependency.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.+-") != std::string::npos)
        throw std::runtime_error("Invalid dependency name");
    auto original = parse(input);
    const ElfInfo before = describe(*original);
    if (original->has_library(dependency)) {
        std::ifstream source(input, std::ios::binary);
        std::ofstream destination(output, std::ios::binary | std::ios::trunc);
        destination << source.rdbuf();
        if (!source || !destination) throw std::runtime_error("Cannot copy existing patched library");
    } else {
        uint64_t page = 0x4000;
        for (const auto& segment : original->segments())
            if (segment.type() == LIEF::ELF::Segment::TYPE::LOAD) page = std::max(page, segment.alignment());
        original.reset();
        auto binary = parse(input, page);
        binary->add_library(dependency);
        binary->write(output);
    }
    const ElfInfo after = inspect_elf(output);
    auto expected = before.needed;
    if (std::find(expected.begin(), expected.end(), dependency) == expected.end()) expected.push_back(dependency);
    auto actual = after.needed;
    std::sort(expected.begin(), expected.end());
    std::sort(actual.begin(), actual.end());
    if (before.abi != after.abi || expected != actual)
        throw std::runtime_error("Patched ELF dependency verification failed");
}
}
