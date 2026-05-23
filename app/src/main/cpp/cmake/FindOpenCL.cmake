include(FindPackageHandleStandardArgs)

set(_openflow_opencl_headers
    "${CMAKE_CURRENT_LIST_DIR}/../third_party/OpenCL-Headers"
)

if (TARGET OpenCL::OpenCL)
    set(OpenCL_FOUND TRUE)
    set(OpenCL_LIBRARIES OpenCL::OpenCL)
    set(OpenCL_INCLUDE_DIRS "${_openflow_opencl_headers}")
    set(OpenCL_VERSION_STRING "3.0")
    find_package_handle_standard_args(OpenCL
        REQUIRED_VARS OpenCL_LIBRARIES OpenCL_INCLUDE_DIRS
        VERSION_VAR OpenCL_VERSION_STRING
    )
    return()
endif()

find_path(OpenCL_INCLUDE_DIR
    NAMES CL/cl.h
    HINTS "${_openflow_opencl_headers}"
)

find_library(OpenCL_LIBRARY
    NAMES OpenCL
)

find_package_handle_standard_args(OpenCL
    REQUIRED_VARS OpenCL_LIBRARY OpenCL_INCLUDE_DIR
)

if (OpenCL_FOUND)
    set(OpenCL_LIBRARIES "${OpenCL_LIBRARY}")
    set(OpenCL_INCLUDE_DIRS "${OpenCL_INCLUDE_DIR}")
endif()
