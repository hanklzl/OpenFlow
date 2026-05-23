include(FindPackageHandleStandardArgs)

set(_openflow_spirv_header_hints "")

if (DEFINED CMAKE_ANDROID_NDK)
    list(APPEND _openflow_spirv_header_hints
        "${CMAKE_ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
endif()

if (DEFINED ANDROID_NDK)
    list(APPEND _openflow_spirv_header_hints
        "${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
endif()

if (DEFINED ENV{ANDROID_NDK_HOME})
    list(APPEND _openflow_spirv_header_hints
        "$ENV{ANDROID_NDK_HOME}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
endif()

if (DEFINED ENV{SPIRV_HEADERS_INCLUDE_DIR})
    list(APPEND _openflow_spirv_header_hints "$ENV{SPIRV_HEADERS_INCLUDE_DIR}")
endif()

find_path(SPIRV_HEADERS_INCLUDE_DIR
    NAMES spirv/unified1/spirv.hpp
    HINTS ${_openflow_spirv_header_hints}
)

find_package_handle_standard_args(SPIRV-Headers
    REQUIRED_VARS SPIRV_HEADERS_INCLUDE_DIR
)

if (SPIRV-Headers_FOUND)
    if (NOT TARGET SPIRV-Headers::SPIRV-Headers)
        add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
        set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_HEADERS_INCLUDE_DIR}"
        )
    endif()
    include_directories(SYSTEM "${SPIRV_HEADERS_INCLUDE_DIR}")
endif()
