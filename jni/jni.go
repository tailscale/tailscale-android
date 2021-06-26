// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

// Package jni implements various helper functions for communicating with the Android JVM
// though JNI.
package jni

import (
	"errors"
	"fmt"
	"reflect"
	"runtime"
	"sync"
	"unicode/utf16"
	"unsafe"
)

/*
#cgo CFLAGS: -Wall

#include <jni.h>
#include <stdlib.h>

static jint jni_AttachCurrentThread(JavaVM *vm, JNIEnv **p_env, void *thr_args) {
	return (*vm)->AttachCurrentThread(vm, p_env, thr_args);
}

static jint jni_DetachCurrentThread(JavaVM *vm) {
	return (*vm)->DetachCurrentThread(vm);
}

static jint jni_GetEnv(JavaVM *vm, JNIEnv **env, jint version) {
	return (*vm)->GetEnv(vm, (void **)env, version);
}

static jclass jni_FindClass(JNIEnv *env, const char *name) {
	return (*env)->FindClass(env, name);
}

static jthrowable jni_ExceptionOccurred(JNIEnv *env) {
	return (*env)->ExceptionOccurred(env);
}

static void jni_ExceptionClear(JNIEnv *env) {
	(*env)->ExceptionClear(env);
}

static jclass jni_GetObjectClass(JNIEnv *env, jobject obj) {
	return (*env)->GetObjectClass(env, obj);
}

static jmethodID jni_GetMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
	return (*env)->GetMethodID(env, clazz, name, sig);
}

static jmethodID jni_GetStaticMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
	return (*env)->GetStaticMethodID(env, clazz, name, sig);
}

static jsize jni_GetStringLength(JNIEnv *env, jstring str) {
	return (*env)->GetStringLength(env, str);
}

static const jchar *jni_GetStringChars(JNIEnv *env, jstring str) {
	return (*env)->GetStringChars(env, str, NULL);
}

static jstring jni_NewString(JNIEnv *env, const jchar *unicodeChars, jsize len) {
	return (*env)->NewString(env, unicodeChars, len);
}

static jboolean jni_IsSameObject(JNIEnv *env, jobject ref1, jobject ref2) {
	return (*env)->IsSameObject(env, ref1, ref2);
}

static jobject jni_NewGlobalRef(JNIEnv *env, jobject obj) {
	return (*env)->NewGlobalRef(env, obj);
}

static void jni_DeleteGlobalRef(JNIEnv *env, jobject obj) {
	(*env)->DeleteGlobalRef(env, obj);
}

static void jni_CallStaticVoidMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args) {
	(*env)->CallStaticVoidMethodA(env, cls, method, args);
}

static jint jni_CallStaticIntMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args) {
	return (*env)->CallStaticIntMethodA(env, cls, method, args);
}

static jobject jni_CallStaticObjectMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args) {
	return (*env)->CallStaticObjectMethodA(env, cls, method, args);
}

static jobject jni_CallObjectMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	return (*env)->CallObjectMethodA(env, obj, method, args);
}

static jboolean jni_CallBooleanMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	return (*env)->CallBooleanMethodA(env, obj, method, args);
}

static jint jni_CallIntMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	return (*env)->CallIntMethodA(env, obj, method, args);
}

static void jni_CallVoidMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args) {
	(*env)->CallVoidMethodA(env, obj, method, args);
}

static jbyteArray jni_NewByteArray(JNIEnv *env, jsize length) {
	return (*env)->NewByteArray(env, length);
}

static jboolean *jni_GetBooleanArrayElements(JNIEnv *env, jbooleanArray arr) {
	return (*env)->GetBooleanArrayElements(env, arr, NULL);
}

static void jni_ReleaseBooleanArrayElements(JNIEnv *env, jbooleanArray arr, jboolean *elems, jint mode) {
	(*env)->ReleaseBooleanArrayElements(env, arr, elems, mode);
}

static jbyte *jni_GetByteArrayElements(JNIEnv *env, jbyteArray arr) {
	return (*env)->GetByteArrayElements(env, arr, NULL);
}

static jint *jni_GetIntArrayElements(JNIEnv *env, jintArray arr) {
	return (*env)->GetIntArrayElements(env, arr, NULL);
}

static void jni_ReleaseIntArrayElements(JNIEnv *env, jintArray arr, jint *elems, jint mode) {
	(*env)->ReleaseIntArrayElements(env, arr, elems, mode);
}

static jlong *jni_GetLongArrayElements(JNIEnv *env, jlongArray arr) {
	return (*env)->GetLongArrayElements(env, arr, NULL);
}

static void jni_ReleaseLongArrayElements(JNIEnv *env, jlongArray arr, jlong *elems, jint mode) {
	(*env)->ReleaseLongArrayElements(env, arr, elems, mode);
}

static void jni_ReleaseByteArrayElements(JNIEnv *env, jbyteArray arr, jbyte *elems, jint mode) {
	(*env)->ReleaseByteArrayElements(env, arr, elems, mode);
}

static jsize jni_GetArrayLength(JNIEnv *env, jarray arr) {
	return (*env)->GetArrayLength(env, arr);
}

static void jni_DeleteLocalRef(JNIEnv *env, jobject localRef) {
	return (*env)->DeleteLocalRef(env, localRef);
}

static jobject jni_GetObjectArrayElement(JNIEnv *env, jobjectArray array, jsize index) {
	return (*env)->GetObjectArrayElement(env, array, index);
}

static jboolean jni_IsInstanceOf(JNIEnv *env, jobject obj, jclass clazz) {
	return (*env)->IsInstanceOf(env, obj, clazz);
}
*/
import "C"

type JVM C.JavaVM

type Env C.JNIEnv

type (
	Class        C.jclass
	Object       C.jobject
	MethodID     C.jmethodID
	String       C.jstring
	ByteArray    C.jbyteArray
	ObjectArray  C.jobjectArray
	BooleanArray C.jbooleanArray
	LongArray    C.jlongArray
	IntArray     C.jintArray
	Boolean      C.jboolean
	Value        uint64 // All JNI types fit into 64-bits.
)

// Cached class handles.
var classes struct {
	once                      sync.Once
	stringClass, integerClass Class

	integerIntValue MethodID
}

func env(e *Env) *C.JNIEnv {
	return (*C.JNIEnv)(unsafe.Pointer(e))
}

func javavm(vm *JVM) *C.JavaVM {
	return (*C.JavaVM)(unsafe.Pointer(vm))
}

// Do invokes a function with a temporary JVM environment. The
// environment is not valid after the function returns.
func Do(vm *JVM, f func(env *Env) error) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	var env *C.JNIEnv
	if res := C.jni_GetEnv(javavm(vm), &env, C.JNI_VERSION_1_6); res != C.JNI_OK {
		if res != C.JNI_EDETACHED {
			panic(fmt.Errorf("JNI GetEnv failed with error %d", res))
		}
		if C.jni_AttachCurrentThread(javavm(vm), &env, nil) != C.JNI_OK {
			panic(errors.New("runInJVM: AttachCurrentThread failed"))
		}
		defer C.jni_DetachCurrentThread(javavm(vm))
	}

	return f((*Env)(unsafe.Pointer(env)))
}

func Bool(b bool) Boolean {
	if b {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

func varArgs(args []Value) *C.jvalue {
	if len(args) == 0 {
		return nil
	}
	return (*C.jvalue)(unsafe.Pointer(&args[0]))
}

func IsSameObject(e *Env, ref1, ref2 Object) bool {
	same := C.jni_IsSameObject(env(e), C.jobject(ref1), C.jobject(ref2))
	return same == C.JNI_TRUE
}

func CallStaticIntMethod(e *Env, cls Class, method MethodID, args ...Value) (int, error) {
	res := C.jni_CallStaticIntMethodA(env(e), C.jclass(cls), C.jmethodID(method), varArgs(args))
	return int(res), exception(e)
}

func CallStaticVoidMethod(e *Env, cls Class, method MethodID, args ...Value) error {
	C.jni_CallStaticVoidMethodA(env(e), C.jclass(cls), C.jmethodID(method), varArgs(args))
	return exception(e)
}

func CallVoidMethod(e *Env, obj Object, method MethodID, args ...Value) error {
	C.jni_CallVoidMethodA(env(e), C.jobject(obj), C.jmethodID(method), varArgs(args))
	return exception(e)
}

func CallStaticObjectMethod(e *Env, cls Class, method MethodID, args ...Value) (Object, error) {
	res := C.jni_CallStaticObjectMethodA(env(e), C.jclass(cls), C.jmethodID(method), varArgs(args))
	return Object(res), exception(e)
}

func CallObjectMethod(e *Env, obj Object, method MethodID, args ...Value) (Object, error) {
	res := C.jni_CallObjectMethodA(env(e), C.jobject(obj), C.jmethodID(method), varArgs(args))
	return Object(res), exception(e)
}

func CallBooleanMethod(e *Env, obj Object, method MethodID, args ...Value) (bool, error) {
	res := C.jni_CallBooleanMethodA(env(e), C.jobject(obj), C.jmethodID(method), varArgs(args))
	return res == C.JNI_TRUE, exception(e)
}

func CallIntMethod(e *Env, obj Object, method MethodID, args ...Value) (int32, error) {
	res := C.jni_CallIntMethodA(env(e), C.jobject(obj), C.jmethodID(method), varArgs(args))
	return int32(res), exception(e)
}

// GetByteArrayElements returns the contents of the byte array.
func GetByteArrayElements(e *Env, jarr ByteArray) []byte {
	if jarr == 0 {
		return nil
	}
	size := C.jni_GetArrayLength(env(e), C.jarray(jarr))
	elems := C.jni_GetByteArrayElements(env(e), C.jbyteArray(jarr))
	defer C.jni_ReleaseByteArrayElements(env(e), C.jbyteArray(jarr), elems, 0)
	backing := (*(*[1 << 30]byte)(unsafe.Pointer(elems)))[:size:size]
	s := make([]byte, len(backing))
	copy(s, backing)
	return s
}

// GetBooleanArrayElements returns the contents of the boolean array.
func GetBooleanArrayElements(e *Env, jarr BooleanArray) []bool {
	if jarr == 0 {
		return nil
	}
	size := C.jni_GetArrayLength(env(e), C.jarray(jarr))
	elems := C.jni_GetBooleanArrayElements(env(e), C.jbooleanArray(jarr))
	defer C.jni_ReleaseBooleanArrayElements(env(e), C.jbooleanArray(jarr), elems, 0)
	backing := (*(*[1 << 30]C.jboolean)(unsafe.Pointer(elems)))[:size:size]
	r := make([]bool, len(backing))
	for i, b := range backing {
		r[i] = b == C.JNI_TRUE
	}
	return r
}

// GetStringArrayElements returns the contents of the String array.
func GetStringArrayElements(e *Env, jarr ObjectArray) []string {
	var strings []string
	iterateObjectArray(e, jarr, func(e *Env, idx int, item Object) {
		s := GoString(e, String(item))
		strings = append(strings, s)
	})
	return strings
}

// GetIntArrayElements returns the contents of the int array.
func GetIntArrayElements(e *Env, jarr IntArray) []int {
	if jarr == 0 {
		return nil
	}
	size := C.jni_GetArrayLength(env(e), C.jarray(jarr))
	elems := C.jni_GetIntArrayElements(env(e), C.jintArray(jarr))
	defer C.jni_ReleaseIntArrayElements(env(e), C.jintArray(jarr), elems, 0)
	backing := (*(*[1 << 27]C.jint)(unsafe.Pointer(elems)))[:size:size]
	r := make([]int, len(backing))
	for i, l := range backing {
		r[i] = int(l)
	}
	return r
}

// GetLongArrayElements returns the contents of the long array.
func GetLongArrayElements(e *Env, jarr LongArray) []int64 {
	if jarr == 0 {
		return nil
	}
	size := C.jni_GetArrayLength(env(e), C.jarray(jarr))
	elems := C.jni_GetLongArrayElements(env(e), C.jlongArray(jarr))
	defer C.jni_ReleaseLongArrayElements(env(e), C.jlongArray(jarr), elems, 0)
	backing := (*(*[1 << 27]C.jlong)(unsafe.Pointer(elems)))[:size:size]
	r := make([]int64, len(backing))
	for i, l := range backing {
		r[i] = int64(l)
	}
	return r
}

func iterateObjectArray(e *Env, jarr ObjectArray, f func(e *Env, idx int, item Object)) {
	if jarr == 0 {
		return
	}
	size := C.jni_GetArrayLength(env(e), C.jarray(jarr))
	for i := 0; i < int(size); i++ {
		item := C.jni_GetObjectArrayElement(env(e), C.jobjectArray(jarr), C.jint(i))
		f(e, i, Object(item))
		C.jni_DeleteLocalRef(env(e), item)
	}
}

// NewByteArray allocates a Java byte array with the content. It
// panics if the allocation fails.
func NewByteArray(e *Env, content []byte) ByteArray {
	jarr := C.jni_NewByteArray(env(e), C.jsize(len(content)))
	if jarr == 0 {
		panic(fmt.Errorf("jni: NewByteArray(%d) failed", len(content)))
	}
	elems := C.jni_GetByteArrayElements(env(e), jarr)
	defer C.jni_ReleaseByteArrayElements(env(e), jarr, elems, 0)
	backing := (*(*[1 << 30]byte)(unsafe.Pointer(elems)))[:len(content):len(content)]
	copy(backing, content)
	return ByteArray(jarr)
}

// ClassLoader returns a reference to the Java ClassLoader associated
// with obj.
func ClassLoaderFor(e *Env, obj Object) Object {
	cls := GetObjectClass(e, obj)
	getClassLoader := GetMethodID(e, cls, "getClassLoader", "()Ljava/lang/ClassLoader;")
	clsLoader, err := CallObjectMethod(e, Object(obj), getClassLoader)
	if err != nil {
		// Class.getClassLoader should never fail.
		panic(err)
	}
	return Object(clsLoader)
}

// LoadClass invokes the underlying ClassLoader's loadClass method and
// returns the class.
func LoadClass(e *Env, loader Object, class string) (Class, error) {
	cls := GetObjectClass(e, loader)
	loadClass := GetMethodID(e, cls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;")
	name := JavaString(e, class)
	loaded, err := CallObjectMethod(e, loader, loadClass, Value(name))
	if err != nil {
		return 0, err
	}
	return Class(loaded), exception(e)
}

// exception returns an error corresponding to the pending
// exception, and clears it. exceptionError returns nil if no
// exception is pending.
func exception(e *Env) error {
	thr := C.jni_ExceptionOccurred(env(e))
	if thr == 0 {
		return nil
	}
	C.jni_ExceptionClear(env(e))
	cls := GetObjectClass(e, Object(thr))
	toString := GetMethodID(e, cls, "toString", "()Ljava/lang/String;")
	msg, err := CallObjectMethod(e, Object(thr), toString)
	if err != nil {
		return err
	}
	return errors.New(GoString(e, String(msg)))
}

// GetObjectClass returns the Java Class for an Object.
func GetObjectClass(e *Env, obj Object) Class {
	if obj == 0 {
		panic("null object")
	}
	cls := C.jni_GetObjectClass(env(e), C.jobject(obj))
	if err := exception(e); err != nil {
		// GetObjectClass should never fail.
		panic(err)
	}
	return Class(cls)
}

// GetStaticMethodID returns the id for a static method. It panics if the method
// wasn't found.
func GetStaticMethodID(e *Env, cls Class, name, signature string) MethodID {
	mname := C.CString(name)
	defer C.free(unsafe.Pointer(mname))
	msig := C.CString(signature)
	defer C.free(unsafe.Pointer(msig))
	m := C.jni_GetStaticMethodID(env(e), C.jclass(cls), mname, msig)
	if err := exception(e); err != nil {
		panic(err)
	}
	return MethodID(m)
}

// GetMethodID returns the id for a method. It panics if the method
// wasn't found.
func GetMethodID(e *Env, cls Class, name, signature string) MethodID {
	mname := C.CString(name)
	defer C.free(unsafe.Pointer(mname))
	msig := C.CString(signature)
	defer C.free(unsafe.Pointer(msig))
	m := C.jni_GetMethodID(env(e), C.jclass(cls), mname, msig)
	if err := exception(e); err != nil {
		panic(err)
	}
	return MethodID(m)
}

func NewGlobalRef(e *Env, obj Object) Object {
	return Object(C.jni_NewGlobalRef(env(e), C.jobject(obj)))
}

func DeleteGlobalRef(e *Env, obj Object) {
	C.jni_DeleteGlobalRef(env(e), C.jobject(obj))
}

// JavaString converts the string to a JVM jstring.
func JavaString(e *Env, str string) String {
	if str == "" {
		return 0
	}
	utf16Chars := utf16.Encode([]rune(str))
	res := C.jni_NewString(env(e), (*C.jchar)(unsafe.Pointer(&utf16Chars[0])), C.int(len(utf16Chars)))
	return String(res)
}

// GoString converts the JVM jstring to a Go string.
func GoString(e *Env, str String) string {
	if str == 0 {
		return ""
	}
	strlen := C.jni_GetStringLength(env(e), C.jstring(str))
	chars := C.jni_GetStringChars(env(e), C.jstring(str))
	var utf16Chars []uint16
	hdr := (*reflect.SliceHeader)(unsafe.Pointer(&utf16Chars))
	hdr.Data = uintptr(unsafe.Pointer(chars))
	hdr.Cap = int(strlen)
	hdr.Len = int(strlen)
	utf8 := utf16.Decode(utf16Chars)
	return string(utf8)
}
