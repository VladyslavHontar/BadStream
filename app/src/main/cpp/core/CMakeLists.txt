add_library(plohoystream_core STATIC
        version.cpp
        amf0.cpp
        rtmp_handshake.cpp
        rtmp_chunk.cpp
        flv.cpp
        rtmp_client.cpp
        tcp_transport.cpp)
target_include_directories(plohoystream_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(plohoystream_core PUBLIC cxx_std_17)
