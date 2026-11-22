# libkavhook.so, Kav's engine for extensions' hooks (kavhook.cpp), built by build.sh from these sources, each at the
# commit Vector (LSPosed's successor) builds with:
#   LSPlant (LGPL-3.0)     github.com/JingMatrix/LSPlant      7a768e31ffb2852797631b5140b54f9702a78743
#   Dobby (Apache-2.0)     github.com/JingMatrix/Dobby        05a09ac6807a6bb1726350e40ea4b127c1c79809
#   xz-embedded (0BSD)     github.com/tukaani-project/xz-embedded ae63ae3a36ed01724674e8f3d750dc47bf125410
#   elf/ (GPL-3.0)         github.com/JingMatrix/Vector       efb82883071643ca16128ecd588be7c40c1e45e6, native/*/elf
# DEPS is the folder they were fetched into.
cmake_minimum_required(VERSION 3.28)
project(kavhook)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# xz-embedded, for the symbols ART keeps compressed in libart.so's .gnu_debugdata.
set(XZ ${DEPS}/xz-embedded)
add_library(xz_static STATIC ${XZ}/linux/lib/xz/xz_crc32.c ${XZ}/linux/lib/xz/xz_crc64.c
    ${XZ}/linux/lib/xz/xz_dec_lzma2.c ${XZ}/linux/lib/xz/xz_dec_stream.c)
target_compile_options(xz_static PRIVATE -DXZ_USE_CRC64)
target_include_directories(xz_static PRIVATE ${XZ}/linux/include/linux ${XZ}/userspace)

set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(${DEPS}/lsplant/lsplant/src/main/jni lsplant)
target_compile_options(lsplant_static PUBLIC -Wno-gnu-anonymous-struct -Wno-gnu-string-literal-operator-template)

set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
add_subdirectory(${DEPS}/dobby dobby)

add_library(kavhook SHARED kavhook.cpp elf/elf_image.cpp)
target_include_directories(kavhook PRIVATE . ${XZ}/linux/include ${DEPS}/dobby/include)
target_link_libraries(kavhook PRIVATE lsplant_static dobby_static xz_static log)
# Only JNI_OnLoad is exported; pages of 16 KB, as Android 15 and later phones may use.
target_compile_options(kavhook PRIVATE -fvisibility=hidden)
target_link_options(kavhook PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
