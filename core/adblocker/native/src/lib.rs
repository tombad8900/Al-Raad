//! Minimal JNI binding over Brave's `adblock` crate. Network matching only: the Kotlin side
//! passes the request URL, the page (source) URL and the request type; cosmetic rules are a
//! separate step. Handles are boxed engines owned by the Kotlin wrapper.

use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

fn read(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

#[no_mangle]
pub extern "system" fn Java_com_alraad_core_adblocker_impl_NativeAdblock_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    list_text: JString,
) -> jlong {
    match read(&mut env, &list_text) {
        Some(text) => Box::into_raw(Box::new(Engine::new_with_list_text(text))) as jlong,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_alraad_core_adblocker_impl_NativeAdblock_nativeShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let (Some(url), Some(source), Some(kind)) =
        (read(&mut env, &url), read(&mut env, &source_url), read(&mut env, &request_type))
    else {
        return JNI_FALSE;
    };
    // SAFETY: the handle comes from nativeCreate and is not destroyed while in use (Kotlin side holds a lock).
    let engine = unsafe { &*(handle as *const Engine) };
    match Request::new(&url, &source, &kind, "GET") {
        Ok(request) if engine.check_network_request(&request).should_block() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_alraad_core_adblocker_impl_NativeAdblock_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: reclaims the box created in nativeCreate exactly once.
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}
