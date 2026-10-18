// ech_http_jni_v4.c —— 并发安全版 JNI 桥（per-request 独立状态，对齐原版 EchClient 并发复用）
// 依赖官方 libech_http.so（两个 .so 一起进 jniLibs，此处用 extern 直接引用其 eh_* 符号）。
// 编译：clang --target=aarch64-linux-android30 --sysroot=$NDK_SYSROOT -L./ -lech_http -shared -fPIC -O2 -o libech_http_jni.so ech_http_jni_v4.c
//
// v4 相对 v3 的核心改动：
// - 每个请求分配独立 EchCtx（自己的锁/cond/body/req），不再全局单例 → 并发安全
// - 复用全局 client（对齐原版 session 复用），request_start 的 port 参数传 ctx 指针，
//   回调里通过 port 找回对应请求的状态并立即 ack
// - 保留 302 重定向跟随（Location 解析）
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>

/* ---------- Dart_CObject ABI（与 ech_http 回调约定一致，test_ech.c 已验证） ---------- */
typedef enum {
  Dart_CObject_kNull = 0, Dart_CObject_kBool, Dart_CObject_kInt32,
  Dart_CObject_kInt64, Dart_CObject_kDouble, Dart_CObject_kString,
  Dart_CObject_kArray, Dart_CObject_kTypedData, Dart_CObject_kExternalTypedData,
  Dart_CObject_kUnsupported
} Dart_CObject_Type;

typedef enum {
  Dart_TypedData_kInt8 = 0, Dart_TypedData_kUint8, Dart_TypedData_kUint8Clamped,
  Dart_TypedData_kInt16, Dart_TypedData_kUint16, Dart_TypedData_kInt32,
  Dart_TypedData_kUint32, Dart_TypedData_kInt64, Dart_TypedData_kUint64,
  Dart_TypedData_kFloat32, Dart_TypedData_kFloat64, Dart_TypedData_kInvalid = 255
} Dart_TypedData_Type;

typedef struct _Dart_CObject {
  Dart_CObject_Type type;
  union {
    bool as_bool;
    int32_t as_int32;
    int64_t as_int64;
    double as_double;
    struct { const char* as_string; intptr_t length; } as_string;
    struct { intptr_t length; struct _Dart_CObject** values; } as_array;
    struct { Dart_TypedData_Type type; intptr_t length; const uint8_t* values; } as_typed_data;
    struct { intptr_t length; void* peer; void (*callback)(void*, void*); } as_external_typed_data;
  } value;
} Dart_CObject;

/* ---------- ech_http C API（符号由 libech_http.so 提供，链接期解析） ---------- */
typedef struct EhClient EhClient;
typedef struct EhRequest EhRequest;
typedef struct EhOptions {
  const char* url;
  const char* method;
  const char* headers;
  const char* proxy;
  const char* ech_config;
  const char* connect_ip;
  const char* ca_pem;
  const uint8_t* body;
  size_t body_length;
  int64_t timeout_ms;
  int64_t connect_timeout_ms;
  int64_t max_response_bytes;
  bool auto_uncompress;
} EhOptions;
typedef bool (*EhPostCObject)(int64_t port, Dart_CObject* message);
EhClient* eh_client_create(void);
void eh_client_destroy(EhClient*);
EhRequest* eh_request_start(EhClient*, const EhOptions*, EhPostCObject, int64_t);
void eh_request_acknowledge(EhRequest*, size_t);
void eh_request_destroy(EhRequest*);

/* ---------- 每请求独立上下文（port 参数 = ctx 指针，回调经 port 找回本请求状态） ---------- */
typedef struct EchCtx {
  pthread_mutex_t mu;
  pthread_cond_t cv;
  int done;
  int http;
  int status;
  int ech_accepted;
  int is_error;
  unsigned char* body;
  size_t len, cap;
  EhRequest* req;
  char hdr[2048];
  size_t hdr_len;
  char redirect_url[1024];
  struct EchCtx* next;
} EchCtx;

/* 全局 client（复用 session，对齐原版；创建时加锁保护） */
static pthread_mutex_t g_client_mu = PTHREAD_MUTEX_INITIALIZER;
static EhClient* g_client = NULL;

static EchCtx* ctx_new(void) {
  EchCtx* c = (EchCtx*)calloc(1, sizeof(EchCtx));
  if (!c) return NULL;
  pthread_mutex_init(&c->mu, NULL);
  pthread_cond_init(&c->cv, NULL);
  return c;
}

static void ctx_free(EchCtx* c) {
  if (!c) return;
  if (c->body) free(c->body);
  pthread_mutex_destroy(&c->mu);
  pthread_cond_destroy(&c->cv);
  free(c);
}

static void ctx_append_body(EchCtx* c, const uint8_t* d, size_t n) {
  if (c->len + n > c->cap) {
    size_t cap = c->cap ? c->cap : 65536;
    while (cap < c->len + n) cap *= 2;
    unsigned char* nb = (unsigned char*)realloc(c->body, cap);
    if (!nb) return;
    c->body = nb; c->cap = cap;
  }
  memcpy(c->body + c->len, d, n);
  c->len += n;
}

/* 回调：port 即 EchCtx*（eh_request_start 传入），消息 [type, code, ech_accepted, retries, Uint8List] */
static bool on_post(int64_t port, Dart_CObject* msg) {
  EchCtx* c = (EchCtx*)(intptr_t)port;
  if (!c || !msg || msg->type != Dart_CObject_kArray) return false;
  Dart_CObject** v = msg->value.as_array.values;
  int32_t n = msg->value.as_array.length;
  if (!v || n < 2) return false;
  int32_t t = v[0]->value.as_int32;
  int32_t code = v[1]->value.as_int32;

  pthread_mutex_lock(&c->mu);
  if (n >= 3 && v[2]->type == Dart_CObject_kInt32) c->ech_accepted = v[2]->value.as_int32;
  if (t == 2) { /* body chunk */
    if (n >= 5 && v[4]->type == Dart_CObject_kTypedData) {
      const uint8_t* d = v[4]->value.as_typed_data.values;
      size_t ln = (size_t)v[4]->value.as_typed_data.length;
      ctx_append_body(c, d, ln);
      /* 每块立即 ack（背压：不 ack 则 ech_http 不推后续块，大图挂起） */
      if (c->req && ln > 0) {
        eh_request_acknowledge(c->req, ln);
      }
    }
    pthread_mutex_unlock(&c->mu);
    return true;
  }
  if (t == 1) {
    c->status = code;
    c->http = code;
    if (n >= 5 && v[4]->type == Dart_CObject_kTypedData) {
      c->hdr_len = (size_t)v[4]->value.as_typed_data.length;
      size_t cl = c->hdr_len < sizeof(c->hdr) - 1 ? c->hdr_len : sizeof(c->hdr) - 1;
      memcpy(c->hdr, v[4]->value.as_typed_data.values, cl);
      c->hdr[cl] = 0;
    }
  } else if (t == 3) {
    if (c->status == 0) c->status = code;
    c->http = code; c->done = 1;
    pthread_cond_broadcast(&c->cv);
  } else if (t == 4) {
    c->is_error = 1;
    c->http = code;
    c->done = 1;
    pthread_cond_broadcast(&c->cv);
  }
  pthread_mutex_unlock(&c->mu);
  return true;
}

/* 从响应头解析 Location（大小写不敏感） */
static int extract_location(const char* hdr, size_t len, char* out, size_t out_cap) {
  if (!hdr || len == 0) return 0;
  const char* p = hdr;
  const char* end = hdr + len;
  while (p < end) {
    const char* eol = p;
    while (eol < end && *eol != '\r' && *eol != '\n') eol++;
    const char* colon = p;
    while (colon < eol && *colon != ':') colon++;
    if (colon < eol) {
      size_t name_len = (size_t)(colon - p);
      const char* val = colon + 1;
      while (val < eol && (*val == ' ' || *val == '\t')) val++;
      size_t val_len = (size_t)(eol - val);
      if (name_len == 8 && (strncasecmp(p, "location", 8) == 0) &&
          val_len > 0 && val_len < out_cap) {
        memcpy(out, val, val_len);
        out[val_len] = 0;
        return 1;
      }
    }
    p = eol;
    while (p < end && (*p == '\r' || *p == '\n')) p++;
  }
  return 0;
}

/* 单次请求（不跟重定向）：返回 1=完成 0=超时/失败 */
static int do_once(const char* url, const char* cfg, const char* ip, const char* hdrs,
                   EchCtx* c, unsigned char** out_body, size_t* out_len) {
  pthread_mutex_lock(&c->mu);
  c->done = 0; c->http = 0; c->status = 0; c->ech_accepted = 0; c->is_error = 0;
  c->len = 0; c->req = NULL; c->hdr_len = 0; c->redirect_url[0] = 0;
  pthread_mutex_unlock(&c->mu);

  EhOptions o; memset(&o, 0, sizeof(o));
  o.url = url; o.method = "GET";
  o.headers = hdrs ? hdrs : "user-agent: Kazumi/2.3.9 (Android)\r\n";
  o.proxy = ""; o.ech_config = cfg; o.connect_ip = ip; o.ca_pem = "";
  o.timeout_ms = 20000; o.connect_timeout_ms = 12000;
  o.max_response_bytes = 32 * 1024 * 1024;
  o.auto_uncompress = true;

  pthread_mutex_lock(&g_client_mu);
  if (!g_client) g_client = eh_client_create();
  EhClient* cl = g_client;
  pthread_mutex_unlock(&g_client_mu);
  if (!cl) return 0;

  /* port = ctx 指针（非 0，回调经此找回状态） */
  EhRequest* r = eh_request_start(cl, &o, on_post, (int64_t)(intptr_t)c);
  if (!r) return 0;
  pthread_mutex_lock(&c->mu);
  c->req = r;
  pthread_mutex_unlock(&c->mu);

  pthread_mutex_lock(&c->mu);
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  ts.tv_sec += 30;
  while (!c->done) {
    if (pthread_cond_timedwait(&c->cv, &c->mu, &ts) != 0) break;
  }
  int done = c->done, is_err = c->is_error, http = c->http;
  size_t len = c->len;
  /* 复制 body 出来（调用方负责 free） */
  if (done && !is_err && len > 0) {
    unsigned char* nb = (unsigned char*)malloc(len);
    if (!nb) { pthread_mutex_unlock(&c->mu); eh_request_destroy(r); return 0; }
    memcpy(nb, c->body, len);
    *out_body = nb; *out_len = len;
  } else {
    *out_body = NULL; *out_len = 0;
    /* 3xx：存 Location 到 ctx（供上层重定向） */
    if (http >= 300 && http < 400 && c->hdr_len > 0) {
      char loc[1024];
      if (extract_location(c->hdr, c->hdr_len, loc, sizeof(loc))) {
        strncpy(c->redirect_url, loc, sizeof(c->redirect_url) - 1);
        c->redirect_url[sizeof(c->redirect_url) - 1] = 0;
      }
    }
  }
  c->req = NULL;
  pthread_mutex_unlock(&c->mu);

  eh_request_destroy(r);
  return done && !is_err;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kazumi_port_network_EchHttpNative_request(
    JNIEnv* env, jobject thiz,
    jstring jurl, jstring jechConfig, jstring jconnectIp, jstring jheaders,
    jintArray joutMeta) {
  (void)thiz;
  if (!jurl || !jechConfig || !jconnectIp) return NULL;
  const char* url = (*env)->GetStringUTFChars(env, jurl, NULL);
  const char* cfg = (*env)->GetStringUTFChars(env, jechConfig, NULL);
  const char* ip = (*env)->GetStringUTFChars(env, jconnectIp, NULL);
  const char* hdrs = jheaders ? (*env)->GetStringUTFChars(env, jheaders, NULL) : NULL;
  if (!url || !cfg || !ip) return NULL;

  EchCtx* c = ctx_new();
  if (!c) {
    (*env)->ReleaseStringUTFChars(env, jurl, url);
    (*env)->ReleaseStringUTFChars(env, jechConfig, cfg);
    (*env)->ReleaseStringUTFChars(env, jconnectIp, ip);
    if (hdrs) (*env)->ReleaseStringUTFChars(env, jheaders, hdrs);
    return NULL;
  }

  const char* cur_url = url;
  int redirects = 0;
  unsigned char* body = NULL;
  size_t body_len = 0;
  int http = 0, ech_ok = 0, is_err = 0;
  char redirect_buf[1024] = "";

  while (redirects < 6) {
    body = NULL;
    int ok = do_once(cur_url, cfg, ip, hdrs, c, &body, &body_len);
    if (!ok) break;
    pthread_mutex_lock(&c->mu);
    http = c->http; ech_ok = c->ech_accepted; is_err = c->is_error;
    if (c->redirect_url[0]) {
      strncpy(redirect_buf, c->redirect_url, sizeof(redirect_buf) - 1);
      redirect_buf[sizeof(redirect_buf) - 1] = 0;
      c->redirect_url[0] = 0;
    }
    pthread_mutex_unlock(&c->mu);
    if (http >= 300 && http < 400 && redirect_buf[0]) {
      /* 相对路径拼接到当前 URL 的 host */
      if (redirect_buf[0] == '/') {
        const char* scheme_end = strstr(cur_url, "://");
        if (scheme_end) {
          const char* host_start = scheme_end + 3;
          const char* host_end = strchr(host_start, '/');
          size_t base_len = host_end ? (size_t)(host_end - cur_url) : strlen(cur_url);
          char base[512];
          size_t bl = base_len < sizeof(base) - 1 ? base_len : sizeof(base) - 1;
          memcpy(base, cur_url, bl); base[bl] = 0;
          snprintf(redirect_buf, sizeof(redirect_buf), "%s%s", base, redirect_buf);
        }
      }
      cur_url = redirect_buf;
      redirects++;
      continue;
    }
    break;
  }

  if (joutMeta) {
    jint meta[4];
    meta[0] = http; meta[1] = ech_ok;
    meta[2] = (body && body_len > 0 && !is_err) ? 0 : (is_err ? 2 : 1);
    meta[3] = (jint)body_len;
    (*env)->SetIntArrayRegion(env, joutMeta, 0, 4, meta);
  }

  jbyteArray out = NULL;
  if (body && body_len > 0) {
    out = (*env)->NewByteArray(env, (jsize)body_len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)body_len, (const jbyte*)body);
  }
  if (body) free(body);

  ctx_free(c);

  (*env)->ReleaseStringUTFChars(env, jurl, url);
  (*env)->ReleaseStringUTFChars(env, jechConfig, cfg);
  (*env)->ReleaseStringUTFChars(env, jconnectIp, ip);
  if (hdrs) (*env)->ReleaseStringUTFChars(env, jheaders, hdrs);
  return out;
}
