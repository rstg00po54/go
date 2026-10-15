#include <jni.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
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
#include "game/board.h"
#include "game/boardhistory.h"
#if defined(USE_MEDIATEK_BACKEND)
#include "neuralnet/mediatekbackend.h"
#endif

#if defined(USE_RKNN_BACKEND)
#define KATAGO_SESSION_JNI(name) Java_com_badukai_engine_KataGoNpuNative_##name
#elif defined(USE_MEDIATEK_BACKEND)
#define KATAGO_SESSION_JNI(name) Java_com_badukai_engine_KataGoMediaTekNative_##name
#elif defined(USE_OPENCL_BACKEND)
#define KATAGO_SESSION_JNI(name) Java_com_badukai_engine_KataGoGpuNative_##name
#else
#define KATAGO_SESSION_JNI(name) Java_com_badukai_engine_KataGoNative_##name
#endif

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

    ~Session() { shutdown(); }

    void shutdown() {
        // Shutdown must finish even when another JNI thread temporarily owns
        // a shared_ptr from nativeReadOutput(). Never rely only on reset().
        std::call_once(shutdownOnce_, [this] {
            inputBuf_.send("quit");
            inputBuf_.close();
            if (worker_.joinable()) worker_.join();
        });
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
    std::once_flag shutdownOnce_;
};

// GTP includes process-level neural-net cleanup. Until global setup is refactored,
// allow only one JNI GTP session per process (the existing ProcessBuilder engine
// remains separate because it is a different OS process).
std::mutex sessionsMutex;
// Serialize complete shutdown/join against creation of the next in-process
// GTP engine. sessionsMutex alone cannot do this: the session was removed
// before its worker had finished releasing process-wide KataGo globals.
std::mutex sessionLifecycleMutex;
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
#if defined(USE_RKNN_BACKEND)
KATAGO_SESSION_JNI(nativeCreateSession)(JNIEnv* env, jclass, jstring model, jstring config, jstring humanModel, jstring rknnModel) {
#elif defined(USE_MEDIATEK_BACKEND)
KATAGO_SESSION_JNI(nativeCreateSession)(JNIEnv* env, jclass, jstring model, jstring config, jstring humanModel, jobject runner, jboolean compareCpu) {
#else
KATAGO_SESSION_JNI(nativeCreateSession)(JNIEnv* env, jclass, jstring model, jstring config, jstring humanModel) {
#endif
    const std::string modelPath = toUtf8(env, model);
    const std::string configPath = toUtf8(env, config);
    const std::string humanModelPath = toUtf8(env, humanModel);
    std::lock_guard<std::mutex> lifecycleLock(sessionLifecycleMutex);
#if defined(USE_RKNN_BACKEND)
    const std::string rknnPath = toUtf8(env, rknnModel);
    if (rknnPath.empty() || setenv("KATAGO_RKNN_MODEL", rknnPath.c_str(), 1) != 0) return 0;
#endif
    if (modelPath.empty() || configPath.empty()) return 0;
    std::lock_guard<std::mutex> lock(sessionsMutex);
    if (!sessions.empty()) return 0;
#if defined(USE_MEDIATEK_BACKEND)
    if (!KataGoMediaTekBridge::install(env, runner, compareCpu == JNI_TRUE)) return 0;
#endif
    try {
        const jlong handle = nextHandle++;
        sessions.emplace(handle, std::make_shared<Session>(modelPath, configPath, humanModelPath));
        return handle;
    } catch (...) {
#if defined(USE_MEDIATEK_BACKEND)
        KataGoMediaTekBridge::reset(env);
#endif
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
KATAGO_SESSION_JNI(nativeSendCommand)(JNIEnv* env, jclass, jlong handle, jstring command) {
    auto session = lookup(handle);
    return session && session->send(toUtf8(env, command)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
KATAGO_SESSION_JNI(nativeReadOutput)(JNIEnv* env, jclass, jlong handle, jint timeoutMs) {
    auto session = lookup(handle);
    if (!session) return nullptr;
    const std::string data = session->read(std::min(60000, std::max(0, static_cast<int>(timeoutMs))));
    if (data.empty() && session->finished()) return nullptr;
    return env->NewStringUTF(data.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
KATAGO_SESSION_JNI(nativeIsSessionAlive)(JNIEnv*, jclass, jlong handle) {
    auto session = lookup(handle);
    return session && session->alive() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
KATAGO_SESSION_JNI(nativeStopSearch)(JNIEnv*, jclass, jlong handle) {
    auto session = lookup(handle);
    // GTP "stop" is handled on the session's input thread. While synchronous
    // genmove is in progress, it can only run after that command finishes.
    return session && session->send("stop") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
KATAGO_SESSION_JNI(nativeDestroySession)(JNIEnv* env, jclass, jlong handle) {
    // Keep new sessions from starting before the old search, GTP and NN
    // threads have fully quit; holding sessionsMutex during join would
    // risk deadlocking unrelated JNI lookups.
    std::lock_guard<std::mutex> lifecycleLock(sessionLifecycleMutex);
    std::shared_ptr<Session> removed;
    {
        std::lock_guard<std::mutex> lock(sessionsMutex);
        auto it = sessions.find(handle);
        if (it == sessions.end()) return;
        removed = std::move(it->second);
        sessions.erase(it);
    }
    // Explicitly wait for shutdown even if a concurrent nativeReadOutput()
    // still holds a shared_ptr. Otherwise a new session can race with GTP's
    // global cleanup despite registry.erase() succeeding.
    removed->shutdown();
    removed.reset();
#if defined(USE_MEDIATEK_BACKEND)
    KataGoMediaTekBridge::reset(env);
#endif
}

// A read-only, strict Benson/pass-alive endgame check. Both CPU and GPU JNI
// libraries export this symbol. No GTP commands, NN estimates, or search are
// used. False means "unconfirmed", not necessarily "unfinished".
extern "C" JNIEXPORT jboolean JNICALL
KATAGO_SESSION_JNI(nativeEndgameAllPassAlive)(JNIEnv* env, jclass, jint size, jbyteArray cells) {
    if (!cells || size < 2 || size > Board::MAX_LEN || !Board::IS_ZOBRIST_INITALIZED
            || env->GetArrayLength(cells) != size * size) return JNI_FALSE;
    std::vector<jbyte> values(static_cast<size_t>(size) * size);
    env->GetByteArrayRegion(cells, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    try {
        Board snapshot(size,size);
        std::vector<Move> stones;
        stones.reserve(values.size());
        for (int y=0; y<size; y++) {
            for (int x=0; x<size; x++) {
                jbyte color = values[static_cast<size_t>(y)*size+x];
                if (color != C_EMPTY && color != C_BLACK && color != C_WHITE) return JNI_FALSE;
                if (color != C_EMPTY) stones.emplace_back(Location::getLoc(x,y,size),color);
            }
        }
        if (!snapshot.setStonesFailIfNoLibs(std::move(stones))) return JNI_FALSE;
        Rules rules = Rules::getTrompTaylorish();
        rules.scoringRule = Rules::SCORING_AREA;
        rules.taxRule = Rules::TAX_NONE;
        rules.multiStoneSuicideLegal = false;
        BoardHistory history(snapshot,P_BLACK,rules,0);
        history.endGameIfAllPassAlive(snapshot);
        return history.isGameFinished && history.isScored && !history.isNoResult ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        // Conservative failure. JNI call must never certify malformed positions.
        return JNI_FALSE;
    }
}
