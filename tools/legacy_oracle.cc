// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
// Test-only harness. Requires the separately downloaded, pinned BoringSSL
// source tree and libcrypto. Neither is a dependency of the Kotlin library.
#include <openssl/rand.h>
#include <array>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

static std::array<uint8_t, 64> entropy;
static int entropy_calls;
static int legacy_oracle_random(uint8_t *out, size_t length) {
  if (length != entropy.size() || entropy_calls++ != 0) std::abort();
  std::memcpy(out, entropy.data(), length);
  return 1;
}

// The pinned production file itself is unchanged. The test translation unit
// replaces only its call to RAND_bytes with exactly specified test bytes.
#define RAND_bytes legacy_oracle_random
#include <crypto/curve25519/spake25519.cc>
#undef RAND_bytes

static std::vector<uint8_t> unhex(const std::string &s) {
  if (s.size() % 2) throw std::runtime_error("odd hex");
  std::vector<uint8_t> out(s.size() / 2);
  for (size_t i = 0; i < out.size(); ++i) {
    unsigned value;
    std::istringstream in(s.substr(2 * i, 2));
    in >> std::hex >> value;
    if (in.fail()) throw std::runtime_error("bad hex");
    out[i] = value;
  }
  return out;
}

static std::string hex(const uint8_t *data, size_t length) {
  const char *digits = "0123456789abcdef";
  std::string out(length * 2, '0');
  for (size_t i = 0; i < length; ++i) {
    out[2 * i] = digits[data[i] >> 4];
    out[2 * i + 1] = digits[data[i] & 15];
  }
  return out;
}

int main() {
  std::string line;
  while (std::getline(std::cin, line)) {
    std::vector<std::string> parts;
    size_t start = 0;
    for (;;) {
      size_t end = line.find('|', start);
      parts.push_back(line.substr(start, end == std::string::npos ? end : end - start));
      if (end == std::string::npos) break;
      start = end + 1;
    }
    if (parts.size() != 7) throw std::runtime_error("expected 7 columns");
    auto own_name = unhex(parts[1]);
    auto peer_name = unhex(parts[2]);
    auto password = unhex(parts[3]);
    auto random_input = unhex(parts[4]);
    auto peer_message = unhex(parts[6]);
    if (random_input.size() != entropy.size()) throw std::runtime_error("expected 64 random bytes");
    std::copy(random_input.begin(), random_input.end(), entropy.begin());
    entropy_calls = 0;
    auto *ctx = SPAKE2_CTX_new(parts[0] == "alice" ? spake2_role_alice : spake2_role_bob,
                               own_name.data(), own_name.size(), peer_name.data(), peer_name.size());
    if (!ctx) throw std::runtime_error("context creation failed");
    ctx->disable_password_scalar_hack = parts[5] == "1";
    uint8_t message[32], key[64];
    size_t message_length = 0, key_length = 0;
    if (!SPAKE2_generate_msg(ctx, message, &message_length, sizeof(message), password.data(), password.size()))
      throw std::runtime_error("generate failed");
    std::cout << hex(message, message_length) << '|' << hex(ctx->private_key, 32) << '|'
              << hex(ctx->password_scalar, 32) << '|' << hex(ctx->password_hash, 64) << '|';
    int success = SPAKE2_process_msg(ctx, key, &key_length, sizeof(key), peer_message.data(), peer_message.size());
    std::cout << success << '|' << (success ? hex(key, key_length) : "") << '\n';
    SPAKE2_CTX_free(ctx);
  }
}
