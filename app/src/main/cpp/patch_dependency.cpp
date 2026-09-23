#include <cstdlib>
__attribute__((constructor)) static void init() { setenv("ZFM_PATCH_DEPENDENCY_LOADED", "yes", 1); }
