#include "elf_patch.hpp"
#include <dlfcn.h>
#include <cstdlib>
#include <iostream>
#include <unistd.h>

int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string(argv[1]) == "inspect") {
            auto info = zfm::inspect_elf(argv[2]);
            std::cout << info.abi << '\n';
            for (const auto& library : info.needed) std::cout << library << '\n';
        } else if (argc == 5 && std::string(argv[1]) == "patch") {
            zfm::patch_elf(argv[2], argv[3], argv[4]);
        } else if ((argc == 3 && std::string(argv[1]) == "load") || (argc == 4 && std::string(argv[1]) == "load-gadget")) {
            void* library = dlopen(argv[2], RTLD_NOW);
            if (!library) throw std::runtime_error(dlerror());
            auto value = reinterpret_cast<int(*)()>(dlsym(library, "patch_fixture_value"));
            if (!value || value() != 73) throw std::runtime_error("Patched library execution failed");
            if (std::string(argv[1]) == "load") {
                const char* loaded = getenv("ZFM_PATCH_DEPENDENCY_LOADED");
                if (!loaded || std::string(loaded) != "yes") throw std::runtime_error("Dependency constructor did not run");
            } else if (access(argv[3], F_OK) != 0) {
                throw std::runtime_error("Gadget did not execute the bundled script");
            }
            std::cout << "PASS: dependency constructor and original function" << std::endl;
            _exit(0);
        } else {
            throw std::runtime_error("Usage: inspect file | patch input output dependency | load file");
        }
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
