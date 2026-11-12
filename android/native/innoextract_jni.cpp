#include <jni.h>
#include <iostream>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "cli/extract.hpp"
#include "setup/version.hpp"
#include "util/console.hpp"
#include "util/log.hpp"

namespace {

std::mutex g_inno_mutex;

class LineInterceptStreambuf : public std::streambuf {
public:
    using LineHandler = void (*)(const std::string&, void*);

    LineInterceptStreambuf(LineHandler handler, void* ctx)
        : handler_(handler), ctx_(ctx) {}

    ~LineInterceptStreambuf() override {
        flushLine();
    }

protected:
    int_type overflow(int_type ch) override {
        if (ch != traits_type::eof()) {
            char c = static_cast<char>(ch);
            if (c == '\n' || c == '\r') {
                flushLine();
            } else {
                current_.push_back(c);
            }
        }
        return ch;
    }

private:
    void flushLine() {
        if (!current_.empty()) {
            if (handler_) {
                handler_(current_, ctx_);
            }
            current_.clear();
        }
    }

    LineHandler handler_;
    void* ctx_;
    std::string current_;
};

struct StreamRedirectGuard {
    std::ostream& stream;
    std::streambuf* oldBuf;

    StreamRedirectGuard(std::ostream& s, std::streambuf* newBuf)
        : stream(s), oldBuf(s.rdbuf(newBuf)) {}

    ~StreamRedirectGuard() {
        stream.rdbuf(oldBuf);
    }
};

struct CallbackCtx {
    JNIEnv* env;
    jobject callback;
    jmethodID onFileMethod;
    int count;
};

void onExtractedLine(const std::string& line, void* rawCtx) {
    auto* ctx = static_cast<CallbackCtx*>(rawCtx);
    if (!ctx || !ctx->callback || !ctx->onFileMethod) return;
    ctx->count++;
    jstring jLine = ctx->env->NewStringUTF(line.c_str());
    if (jLine) {
        ctx->env->CallVoidMethod(ctx->callback, ctx->onFileMethod, jLine, static_cast<jint>(ctx->count));
        ctx->env->DeleteLocalRef(jLine);
        if (ctx->env->ExceptionCheck()) {
            ctx->env->ExceptionClear();
        }
    }
}

void onListedLine(const std::string& line, void* rawVec) {
    auto* vec = static_cast<std::vector<std::string>*>(rawVec);
    if (!line.empty()) {
        vec->push_back(line);
    }
}

void onErrorLine(const std::string& line, void* rawStr) {
    auto* err = static_cast<std::string*>(rawStr);
    if (!line.empty()) {
        if (!err->empty()) *err += "\n";
        *err += line;
    }
}

void initInnoGlobals(bool quietLog) {
    color::init(color::disable, color::disable);
    progress::set_enabled(false);
    logger::debug = false;
    logger::quiet = quietLog;
    logger::total_warnings = 0;
    logger::total_errors = 0;
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_kamremake_android_InnoExtract_nativeListFiles(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jExePath
) {
    std::lock_guard<std::mutex> lock(g_inno_mutex);
    if (!jExePath) return nullptr;

    const char* exeChars = env->GetStringUTFChars(jExePath, nullptr);
    std::string exePath(exeChars ? exeChars : "");
    if (exeChars) env->ReleaseStringUTFChars(jExePath, exeChars);

    initInnoGlobals(true);

    std::vector<std::string> files;
    std::string errText;
    {
        LineInterceptStreambuf outBuf(&onListedLine, &files);
        LineInterceptStreambuf errBuf(&onErrorLine, &errText);
        StreamRedirectGuard guardOut(std::cout, &outBuf);
        StreamRedirectGuard guardErr(std::cerr, &errBuf);

        extract_options o;
        o.list = true;
        o.silent = true;
        o.quiet = true;
        o.extract = false;
        o.gog = false;
        o.gog_galaxy = true;
        o.extract_unknown = true;
        o.preserve_file_times = false;
        o.filenames.set_expand(true);
        o.filenames.set_lowercase(false);

        try {
            process_file(exePath, o);
        } catch (const setup::version_error&) {
            return nullptr;
        } catch (const std::exception&) {
            return nullptr;
        } catch (...) {
            return nullptr;
        }
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(files.size()), stringClass, nullptr);
    for (size_t i = 0; i < files.size(); ++i) {
        jstring item = env->NewStringUTF(files[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_kamremake_android_InnoExtract_nativeExtract(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jExePath,
    jstring jDestDir,
    jobjectArray jIncludes,
    jobject jCallback
) {
    std::lock_guard<std::mutex> lock(g_inno_mutex);
    if (!jExePath || !jDestDir) {
        return env->NewStringUTF("Missing installer path or destination directory.");
    }

    const char* exeChars = env->GetStringUTFChars(jExePath, nullptr);
    std::string exePath(exeChars ? exeChars : "");
    if (exeChars) env->ReleaseStringUTFChars(jExePath, exeChars);

    const char* destChars = env->GetStringUTFChars(jDestDir, nullptr);
    std::string destDir(destChars ? destChars : "");
    if (destChars) env->ReleaseStringUTFChars(jDestDir, destChars);

    initInnoGlobals(true);

    CallbackCtx cbCtx{env, jCallback, nullptr, 0};
    if (jCallback) {
        jclass cbClass = env->GetObjectClass(jCallback);
        cbCtx.onFileMethod = env->GetMethodID(cbClass, "onFile", "(Ljava/lang/String;I)V");
    }

    std::string errText;
    std::string caughtError;
    {
        LineInterceptStreambuf outBuf(&onExtractedLine, &cbCtx);
        LineInterceptStreambuf errBuf(&onErrorLine, &errText);
        StreamRedirectGuard guardOut(std::cout, &outBuf);
        StreamRedirectGuard guardErr(std::cerr, &errBuf);

        extract_options o;
        o.extract = true;
        o.list = true;
        o.silent = true;
        o.quiet = true;
        o.gog = false;
        o.gog_galaxy = true;
        o.extract_unknown = true;
        o.preserve_file_times = false;
        o.collisions = OverwriteCollisions;
        o.output_dir = destDir;
        o.filenames.set_expand(true);
        o.filenames.set_lowercase(false);

        if (jIncludes) {
            jsize len = env->GetArrayLength(jIncludes);
            for (jsize i = 0; i < len; ++i) {
                auto jInc = static_cast<jstring>(env->GetObjectArrayElement(jIncludes, i));
                if (jInc) {
                    const char* incChars = env->GetStringUTFChars(jInc, nullptr);
                    if (incChars) {
                        o.include.emplace_back(incChars);
                        env->ReleaseStringUTFChars(jInc, incChars);
                    }
                    env->DeleteLocalRef(jInc);
                }
            }
        }

        try {
            process_file(exePath, o);
        } catch (const std::ios_base::failure& e) {
            caughtError = std::string("Stream error while extracting installer: ") + e.what();
        } catch (const format_error& e) {
            caughtError = e.what();
        } catch (const setup::version_error&) {
            caughtError = "Unsupported Inno Setup installer version.";
        } catch (const std::exception& e) {
            caughtError = e.what();
        } catch (...) {
            caughtError = "Unknown error while extracting Inno Setup installer.";
        }
    }

    if (!caughtError.empty()) {
        return env->NewStringUTF(caughtError.c_str());
    }
    if (logger::total_errors > 0) {
        std::string msg = errText.empty() ? "Extraction finished with errors." : errText;
        return env->NewStringUTF(msg.c_str());
    }
    return nullptr;
}
