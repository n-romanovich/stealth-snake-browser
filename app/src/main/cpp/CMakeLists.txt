cmake_minimum_required(VERSION 3.22.1)
project(snake_pin_kdf C)

set(SNAKE_PIN_SOURCES
    snake_pin_kdf.c
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND SNAKE_PIN_SOURCES snake_pin_sha256_crypto.c)
    set_source_files_properties(snake_pin_sha256_crypto.c PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crypto")
    set_source_files_properties(snake_pin_kdf.c PROPERTIES COMPILE_OPTIONS "-march=armv8-a")
endif()

add_library(snake_pin_kdf SHARED ${SNAKE_PIN_SOURCES})

target_compile_options(snake_pin_kdf PRIVATE
    -std=c11
    -O3
    -fvisibility=hidden
    -Wall
    -Wextra
    -Werror
)

target_link_options(snake_pin_kdf PRIVATE
    -Wl,-z,max-page-size=16384
)
