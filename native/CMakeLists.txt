
cmake_minimum_required(VERSION 3.20)

project(WasapiCapture LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(WasapiCapture SHARED
    WasapiCapture.cpp
)

target_include_directories(WasapiCapture PRIVATE
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(WasapiCapture PRIVATE
    ole32
    uuid
)

# Готовая DLL лежит отдельно от служебных файлов CMake.
set_target_properties(WasapiCapture PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_CURRENT_SOURCE_DIR}/release"
)
