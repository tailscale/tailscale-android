// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package jni

// Package jni implements various helper functions for communicating with the Android JVM
// though JNI.

import (
	"errors"
	"fmt"
	"reflect"
	"runtime"
	"unicode/utf16"
	"unsafe"
)

/*
#cgo CFLAGS: -Wall

#include <jni.h>
#include <stdlib.h>

__attribute__ ((visibility ("hidden"))) jint _jni_GetEnv(JavaVM *vm, JNIEnv **env, jint version);
__attribute__ ((visibility ("hidden"))) jint _jni_AttachCurrentThread(JavaVM *vm, JNIEnv **p_env, void *thr_args);
__attribute__ ((visibility ("hidden"))) jint _jni_DetachCurrentThread(JavaVM *vm);
__attribute__ ((visibility ("hidden"))) jclass _jni_FindClass(JNIEnv *env, const char *name);
__attribute__ ((visibility ("hidden"))) jthrowable _jni_ExceptionOccurred(JNIEnv *env);
__attribute__ ((visibility ("hidden"))) void _jni_ExceptionClear(JNIEnv *env);
__attribute__ ((visibility ("hidden"))) jclass _jni_GetObjectClass(JNIEnv *env, jobject obj);
__attribute__ ((visibility ("hidden"))) jmethodID _jni_GetMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig);
__attribute__ ((visibility ("hidden"))) jmethodID _jni_GetStaticMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig);
__attribute__ ((visibility ("hidden"))) jsize _jni_GetStringLength(JNIEnv *env, jstring str);
__attribute__ ((visibility ("hidden"))) const jchar *_jni_GetStringChars(JNIEnv *env, jstring str);
__attribute__ ((visibility ("hidden"))) jstring _jni_NewString(JNIEnv *env, const jchar *unicodeChars, jsize len);
__attribute__ ((visibility ("hidden"))) jboolean _jni_IsSameObject(JNIEnv *env, jobject ref1, jobject ref2);
__attribute__ ((visibility ("hidden"))) jobject _jni_NewGlobalRef(JNIEnv *env, jobject obj);
__attribute__ ((visibility ("hidden"))) void _jni_DeleteGlobalRef(JNIEnv *env, jobject obj);
__attribute__ ((visibility ("hidden"))) jint _jni_CallStaticIntMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args);
__attribute__ ((visibility ("hidden"))) jobject _jni_CallStaticObjectMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args);
__attribute__ ((visibility ("hidden"))) void _jni_CallStaticVoidMethodA(JNIEnv *env, jclass cls, jmethodID method, jvalue *args);
__attribute__ ((visibility ("hidden"))) jobject _jni_CallObjectMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args);
__attribute__ ((visibility ("hidden"))) jboolean _jni_CallBooleanMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args);
__attribute__ ((visibility ("hidden"))) jint _jni_CallIntMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args);
__attribute__ ((visibility ("hidden"))) void _jni_CallVoidMethodA(JNIEnv *env, jobject obj, jmethodID method, jvalue *args);
__attribute__ ((visibility ("hidden"))) jbyteArray _jni_NewByteArray(JNIEnv *env, jsize length);
__attribute__ ((visibility ("hidden"))) jbyte *_jni_GetByteArrayElements(JNIEnv *env, jbyteArray arr);
__attribute__ ((visibility ("hidden"))) void _jni_ReleaseByteArrayElements(JNIEnv *env, jbyteArray arr, jbyte *elems, jint mode);
__attribute__ ((visibility ("hidden"))) jsize _jni_GetArrayLength(JNIEnv *env, jarray arr);
*/
import "C"

type JVM struct {
	jvm *C.JavaVM
}

type Env struct {
	env *C.JNIEnv
}

type (
	Class     C.jclass
	Object    C.jobject
	MethodID  C.jmethodID
	String    C.jstring
	ByteArray C.jbyteArray
	Boolean   C.jboolean
	Value     uint64 // All JNI types fit into 64-bits.
)

const (
	True  Boolean = C.JNI_TRUE
	False Boolean = C.JNI_FALSE
)

func JVMFor(jvmPtr uintptr) JVM {
	return JVM{
		jvm: (*C.JavaVM)(unsafe.Pointer(jvmPtr)),
	}
}

func EnvFor(envPtr uintptr) Env {
	return Env{
		env: (*C.JNIEnv)(unsafe.Pointer(envPtr)),
	}
}

// Do invokes a function with a temporary JVM environment. The
// environment is not valid after the function returns.
func Do(vm JVM, f func(env Env) error) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	var env *C.JNIEnv
	if res := C._jni_GetEnv(vm.jvm, &env, C.JNI_VERSION_1_6); res != C.JNI_OK {
		if res != C.JNI_EDETACHED {
			panic(fmt.Errorf("JNI GetEnv failed with error %d", res))
		}
		if C._jni_AttachCurrentThread(vm.jvm, &env, nil) != C.JNI_OK {
			panic(errors.New("runInJVM: AttachCurrentThread failed"))
		}
		defer C._jni_DetachCurrentThread(vm.jvm)
	}

	return f(Env{env})
}

func varArgs(args []Value) *C.jvalue {
	if len(args) == 0 {
		return nil
	}
	return (*C.jvalue)(unsafe.Pointer(&args[0]))
}

func IsSameObject(e Env, ref1, ref2 Object) bool {
	same := C._jni_IsSameObject(e.env, C.jobject(ref1), C.jobject(ref2))
	return same == C.JNI_TRUE
}

func CallStaticIntMethod(e Env, cls Class, method MethodID, args ...Value) (int, error) {
	res := C._jni_CallStaticIntMethodA(e.env, C.jclass(cls), C.jmethodID(method), varArgs(args))
	return int(res), exception(e)
}

func CallStaticVoidMethod(e Env, cls Class, method MethodID, args ...Value) error {
	C._jni_CallStaticVoidMethodA(e.env, C.jclass(cls), C.jmethodID(method), varArgs(args))
	return exception(e)
}

func CallVoidMethod(e Env, obj Object, method MethodID, args ...Value) error {
	C._jni_CallVoidMethodA(e.env, C.jobject(obj), C.jmethodID(method), varArgs(args))
	return exception(e)
}

func CallStaticObjectMethod(e Env, cls Class, method MethodID, args ...Value) (Object, error) {
	res := C._jni_CallStaticObjectMethodA(e.env, C.jclass(cls), C.jmethodID(method), varArgs(args))
	return Object(res), exception(e)
}

func CallObjectMethod(e Env, obj Object, method MethodID, args ...Value) (Object, error) {
	res := C._jni_CallObjectMethodA(e.env, C.jobject(obj), C.jmethodID(method), varArgs(args))
	return Object(res), exception(e)
}

func CallBooleanMethod(e Env, obj Object, method MethodID, args ...Value) (bool, error) {
	res := C._jni_CallBooleanMethodA(e.env, C.jobject(obj), C.jmethodID(method), varArgs(args))
	return res == C.JNI_TRUE, exception(e)
}

func CallIntMethod(e Env, obj Object, method MethodID, args ...Value) (int32, error) {
	res := C._jni_CallIntMethodA(e.env, C.jobject(obj), C.jmethodID(method), varArgs(args))
	return int32(res), exception(e)
}

// GetByteArrayElements returns the contents of the array.
func GetByteArrayElements(e Env, jarr ByteArray) []byte {
	size := C._jni_GetArrayLength(e.env, C.jarray(jarr))
	elems := C._jni_GetByteArrayElements(e.env, C.jbyteArray(jarr))
	defer C._jni_ReleaseByteArrayElements(e.env, C.jbyteArray(jarr), elems, 0)
	backing := (*(*[1 << 30]byte)(unsafe.Pointer(elems)))[:size:size]
	s := make([]byte, len(backing))
	copy(s, backing)
	return s
}

// NewByteArray allocates a Java byte array with the content. It
// panics if the allocation fails.
func NewByteArray(e Env, content []byte) ByteArray {
	jarr := C._jni_NewByteArray(e.env, C.jsize(len(content)))
	if jarr == 0 {
		panic(fmt.Errorf("jni: NewByteArray(%d) failed", len(content)))
	}
	elems := C._jni_GetByteArrayElements(e.env, jarr)
	defer C._jni_ReleaseByteArrayElements(e.env, jarr, elems, 0)
	backing := (*(*[1 << 30]byte)(unsafe.Pointer(elems)))[:len(content):len(content)]
	copy(backing, content)
	return ByteArray(jarr)
}

// ClassLoader returns a reference to the Java ClassLoader associated
// with obj.
func ClassLoaderFor(e Env, obj Object) Object {
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
func LoadClass(e Env, loader Object, class string) (Class, error) {
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
func exception(e Env) error {
	thr := C._jni_ExceptionOccurred(e.env)
	if thr == 0 {
		return nil
	}
	C._jni_ExceptionClear(e.env)
	cls := GetObjectClass(e, Object(thr))
	toString := GetMethodID(e, cls, "toString", "()Ljava/lang/String;")
	msg, err := CallObjectMethod(e, Object(thr), toString)
	if err != nil {
		return err
	}
	return errors.New(GoString(e, String(msg)))
}

// GetObjectClass returns the Java Class for an Object.
func GetObjectClass(e Env, obj Object) Class {
	if obj == 0 {
		panic("null object")
	}
	cls := C._jni_GetObjectClass(e.env, C.jobject(obj))
	if err := exception(e); err != nil {
		// GetObjectClass should never fail.
		panic(err)
	}
	return Class(cls)
}

// GetStaticMethodID returns the id for a static method. It panics if the method
// wasn't found.
func GetStaticMethodID(e Env, cls Class, name, signature string) MethodID {
	mname := C.CString(name)
	defer C.free(unsafe.Pointer(mname))
	msig := C.CString(signature)
	defer C.free(unsafe.Pointer(msig))
	m := C._jni_GetStaticMethodID(e.env, C.jclass(cls), mname, msig)
	if err := exception(e); err != nil {
		panic(err)
	}
	return MethodID(m)
}

// GetMethodID returns the id for a method. It panics if the method
// wasn't found.
func GetMethodID(e Env, cls Class, name, signature string) MethodID {
	mname := C.CString(name)
	defer C.free(unsafe.Pointer(mname))
	msig := C.CString(signature)
	defer C.free(unsafe.Pointer(msig))
	m := C._jni_GetMethodID(e.env, C.jclass(cls), mname, msig)
	if err := exception(e); err != nil {
		panic(err)
	}
	return MethodID(m)
}

func NewGlobalRef(e Env, obj Object) Object {
	return Object(C._jni_NewGlobalRef(e.env, C.jobject(obj)))
}

func DeleteGlobalRef(e Env, obj Object) {
	C._jni_DeleteGlobalRef(e.env, C.jobject(obj))
}

// JavaString converts the string to a JVM jstring.
func JavaString(e Env, str string) String {
	if str == "" {
		return 0
	}
	utf16Chars := utf16.Encode([]rune(str))
	res := C._jni_NewString(e.env, (*C.jchar)(unsafe.Pointer(&utf16Chars[0])), C.int(len(utf16Chars)))
	return String(res)
}

// GoString converts the JVM jstring to a Go string.
func GoString(e Env, str String) string {
	if str == 0 {
		return ""
	}
	strlen := C._jni_GetStringLength(e.env, C.jstring(str))
	chars := C._jni_GetStringChars(e.env, C.jstring(str))
	var utf16Chars []uint16
	hdr := (*reflect.SliceHeader)(unsafe.Pointer(&utf16Chars))
	hdr.Data = uintptr(unsafe.Pointer(chars))
	hdr.Cap = int(strlen)
	hdr.Len = int(strlen)
	utf8 := utf16.Decode(utf16Chars)
	return string(utf8)
}
