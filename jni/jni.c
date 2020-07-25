#include <jni.h>

jint _jni_AttachCurrentThread(JavaVM *vm, JNIEnv **p_env, void *thr_args) {
	return (*vm)->AttachCurrentThread(vm, p_env, thr_args);
}

jint _jni_DetachCurrentThread(JavaVM *vm) {
	return (*vm)->DetachCurrentThread(vm);
}

jint _jni_GetEnv(JavaVM *vm, JNIEnv **env, jint version) {
	return (*vm)->GetEnv(vm, (void **)env, version);
}

jclass _jni_FindClass(JNIEnv *env, const char *name) {
	return (*env)->FindClass(env, name);
}

jthrowable _jni_ExceptionOccurred(JNIEnv *env) {
	return (*env)->ExceptionOccurred(env);
}

void _jni_ExceptionClear(JNIEnv *env) {
	(*env)->ExceptionClear(env);
}

jclass _jni_GetObjectClass(JNIEnv *env, jobject obj) {
	return (*env)->GetObjectClass(env, obj);
}

jmethodID _jni_GetMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
	return (*env)->GetMethodID(env, clazz, name, sig);
}

jmethodID _jni_GetStaticMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
	return (*env)->GetStaticMethodID(env, clazz, name, sig);
}

jsize _jni_GetStringLength(JNIEnv *env, jstring str) {
	return (*env)->GetStringLength(env, str);
}

const jchar *_jni_GetStringChars(JNIEnv *env, jstring str) {
	return (*env)->GetStringChars(env, str, NULL);
}

jstring _jni_NewString(JNIEnv *env, const jchar *unicodeChars, jsize len) {
	return (*env)->NewString(env, unicodeChars, len);
}

jboolean _jni_IsSameObject(JNIEnv *env, jobject ref1, jobject ref2) {
	return (*env)->IsSameObject(env, ref1, ref2);
}

jobject _jni_NewGlobalRef(JNIEnv *env, jobject obj) {
	return (*env)->NewGlobalRef(env, obj);
}

void _jni_DeleteGlobalRef(JNIEnv *env, jobject obj) {
	(*env)->DeleteGlobalRef(env, obj);
}

void _jni_CallStaticVoidMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args) {
	(*env)->CallStaticVoidMethodA(env, cls, method, args);
}

jint _jni_CallStaticIntMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args) {
	return (*env)->CallStaticIntMethodA(env, cls, method, args);
}

jobject _jni_CallStaticObjectMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args) {
	return (*env)->CallStaticObjectMethodA(env, cls, method, args);
}

jobject _jni_CallObjectMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	return (*env)->CallObjectMethodA(env, obj, method, args);
}

jboolean _jni_CallBooleanMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	return (*env)->CallBooleanMethodA(env, obj, method, args);
}

jint _jni_CallIntMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	return (*env)->CallIntMethodA(env, obj, method, args);
}

void _jni_CallVoidMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	(*env)->CallVoidMethodA(env, obj, method, args);
}

jbyteArray _jni_NewByteArray(JNIEnv *env, jsize length) {
	return (*env)->NewByteArray(env, length);
}

jbyte *_jni_GetByteArrayElements(JNIEnv *env, jbyteArray arr) {
	return (*env)->GetByteArrayElements(env, arr, NULL);
}

void _jni_ReleaseByteArrayElements(JNIEnv *env, jbyteArray arr, jbyte *elems, jint mode) {
	(*env)->ReleaseByteArrayElements(env, arr, elems, mode);
}

jsize _jni_GetArrayLength(JNIEnv *env, jarray arr) {
	return (*env)->GetArrayLength(env, arr);
}
