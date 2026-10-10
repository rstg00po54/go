#include <jni.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <istream>
#include <memory>
#include <mutex>
#include <ostream>
#include <streambuf>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "command/gtp_io.h"

namespace {

class QueueInput : public std::streambuf {
public:
    bool send(std::string line) {
        if (line.empty() || line.find('\n') != std::string::npos || line.find('\r') != std::string::npos) return false;
        line.push_back('\n');
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (closed_) return false;
            pending_.push_back(std::move(line));
        }
        changed_.notify_one();
        return true;
    }

    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            closed_ = true;
        }
        changed_.notify_all();
    }

protected:
    int_type underflow() override {
        if (gptr() != nullptr && gptr() < egptr()) return traits_type::to_int_type(*gptr());
        std::unique_lock<std::mutex> lock(mutex_);
        changed_.wait(lock, [this] { return closed_ || !pending_.empty(); });
        if (pending_.empty()) return traits_type::eof();
        current_ = std::move(pending_.front());
        pending_.pop_front();
        char* first = &current_[0];
        setg(first, first, first + current_.size());
        return traits_type::to_int_type(*gptr());
    }

private:
    std::mutex mutex_;
    std::condition_variable changed_;
    std::deque<std::string> pending_;
    std::string current_;
    bool closed_ = false;
};

class QueueOutput : public std::streambuf {
public:
    std::string read(int timeoutMs) {
        std::unique_lock<std::mutex> lock(mutex_);
        changed_.wait_for(lock, std::chrono::milliseconds(std::max(0, timeoutMs)),
                          [this] { return !pending_.empty() || closed_; });
        if (pending_.empty()) return std::string();
        // Bound each JNI transfer without losing the rest of a long analysis response.
        const size_t count = std::min(pending_.size(), static_cast<size_t>(65536));
        std::string out = pending_.substr(0, count);
        pending_.erase(0, count);
        return out;
    }

    bool isClosedAndEmpty() {
        std::lock_guard<std::mutex> lock(mutex_);
        return closed_ && pending_.empty();
    }

    bool isOpen() {
        std::lock_guard<std::mutex> lock(mutex_);
        return !closed_;
    }

    void message(const std::string& s) { append(s.data(), s.size()); }

    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            closed_ = true;
        }
        changed_.notify_all();
    }

protected:
    std::streamsize xsputn(const char* s, std::streamsize count) override {
        if (count > 0) append(s, static_cast<size_t>(count));
        return count;
    }

    int_type overflow(int_type ch) override {
        if (traits_type::eq_int_type(ch, traits_type::eof())) return traits_type::not_eof(ch);
        char c = traits_type::to_char_type(ch);
        append(&c, 1);
        return ch;
    }

    int sync() override { changed_.notify_all(); return 0; }

private:
    void append(const char* s, size_t n) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            // GTP output is consumed by Java. Keep bounded if nobody is polling.
            constexpr size_t MAX_PENDING = 4 * 1024 * 1024;
            if (n >= MAX_PENDING) {
                pending_.assign(s + n - MAX_PENDING, MAX_PENDING);
            } else {
                if (pending_.size() + n > MAX_PENDING)
                    pending_.erase(0, pending_.size() + n - MAX_PENDING);
                pending_.append(s, n);
            }
        }
        changed_.notify_all();
    }

    std::mutex mutex_;
    std::condition_variable changed_;
    std::string pending_;
    bool closed_ = false;
};

class Session {
public:
    Session(std::string model, std::string config, std::string humanModel)
        : input_(&inputBuf_), output_(&outputBuf_),
          worker_([this, model, config, humanModel] {
              try {
                  // TCLAP removes args[0] as the executable/subcommand name.
                  // Omitting "gtp" caused it to treat "-model" as the name,
                  // fail on the model path, and call exit(1) on the Android APP.
                  std::vector<std::string> args = {"gtp", "-model", model, "-config", config};
                  if (!humanModel.empty()) { args.push_back("-human-model"); args.push_back(humanModel); }
                  const int result = MainCmds::gtpWithIO(args, input_, output_);
                  if (result != 0) outputBuf_.message("? JNI GTP exited with code " + std::to_string(result) + "\n\n");
              } catch (const std::exception& e) {
                  outputBuf_.message(std::string("? JNI GTP exception: ") + e.what() + "\n\n");
              } catch (...) {
                  outputBuf_.message("? JNI GTP unknown exception\n\n");
              }
              outputBuf_.close();
          }) {}

    ~Session() {
        // Send quit, then EOF; GTP will clean up the bot and NN evaluator.
        inputBuf_.send("quit");
        inputBuf_.close();
        if (worker_.joinable()) worker_.join();
    }

    bool send(const std::string& line) { return inputBuf_.send(line); }
    std::string read(int timeoutMs) { return outputBuf_.read(timeoutMs); }
    bool finished() { return outputBuf_.isClosedAndEmpty(); }
    bool alive() { return outputBuf_.isOpen(); }

private:
    QueueInput inputBuf_;
    QueueOutput outputBuf_;
    std::istream input_;
    std::ostream output_;
    std::thread worker_;
};

// GTP includes process-level neural-net cleanup. Until global setup is refactored,
// allow only one JNI GTP session per process (the existing ProcessBuilder engine
// remains separate because it is a different OS process).
std::mutex sessionsMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
jlong nextHandle = 1;

std::shared_ptr<Session> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    const auto it = sessions.find(handle);
    return it == sessions.end() ? nullptr : it->second;
}

std::string toUtf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string text(chars);
    env->ReleaseStringUTFChars(value, chars);
    return text;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_badukai_engine_KataGoNative_nativeCreateSession(JNIEnv* env, jclass, jstring model, jstring config, jstring humanModel) {
    const std::string modelPath = toUtf8(env, model);
    const std::string configPath = toUtf8(env, config);
    const std::string humanModelPath = toUtf8(env, humanModel);
    if (modelPath.empty() || configPath.empty()) return 0;
    std::lock_guard<std::mutex> lock(sessionsMutex);
    if (!sessions.empty()) return 0;
    try {
        const jlong handle = nextHandle++;
        sessions.emplace(handle, std::make_shared<Session>(modelPath, configPath, humanModelPath));
        return handle;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_badukai_engine_KataGoNative_nativeSendCommand(JNIEnv* env, jclass, jlong handle, jstring command) {
    auto session = lookup(handle);
    return session && session->send(toUtf8(env, command)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_badukai_engine_KataGoNative_nativeReadOutput(JNIEnv* env, jclass, jlong handle, jint timeoutMs) {
    auto session = lookup(handle);
    if (!session) return nullptr;
    const std::string data = session->read(std::min(60000, std::max(0, static_cast<int>(timeoutMs))));
    if (data.empty() && session->finished()) return nullptr;
    return env->NewStringUTF(data.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_badukai_engine_KataGoNative_nativeIsSessionAlive(JNIEnv*, jclass, jlong handle) {
    auto session = lookup(handle);
    return session && session->alive() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_badukai_engine_KataGoNative_nativeStopSearch(JNIEnv*, jclass, jlong handle) {
    auto session = lookup(handle);
    // GTP "stop" is handled on the session's input thread. While synchronous
    // genmove is in progress, it can only run after that command finishes.
    return session && session->send("stop") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_badukai_engine_KataGoNative_nativeDestroySession(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<Session> removed;
    {
        std::lock_guard<std::mutex> lock(sessionsMutex);
        auto it = sessions.find(handle);
        if (it == sessions.end()) return;
        removed = std::move(it->second);
        sessions.erase(it);
    }
    // Destroy outside the registry mutex; joining the native search can block.
    removed.reset();
}
