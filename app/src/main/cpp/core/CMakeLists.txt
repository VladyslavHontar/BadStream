add_library(plohoystream_core STATIC
        version.cpp
        amf0.cpp
        rtmp_handshake.cpp
        rtmp_chunk.cpp
        flv.cpp
        hevc.cpp
        video_codec.cpp
        rtmp_client.cpp
        tcp_transport.cpp
        stream_session.cpp
        mp4_writer.cpp)
target_include_directories(plohoystream_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(plohoystream_core PUBLIC cxx_std_17)
