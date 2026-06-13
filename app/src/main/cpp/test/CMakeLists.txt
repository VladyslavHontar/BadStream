cmake_minimum_required(VERSION 3.22)
project(plohoystream_core_tests CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.15.2)
FetchContent_MakeAvailable(googletest)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../core core_build)

enable_testing()
add_executable(core_tests
        test_smoke.cpp
        test_byte_writer.cpp
        test_amf0.cpp
        test_transport.cpp
        test_rtmp_handshake.cpp
        test_rtmp_chunk.cpp
        test_flv.cpp
        test_rtmp_client.cpp)
target_link_libraries(core_tests PRIVATE plohoystream_core GTest::gtest_main)
include(GoogleTest)
gtest_discover_tests(core_tests)
