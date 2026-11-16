import 'dart:async';

import 'package:flutter/services.dart';

/// Voice-to-text input for the chat composer (Android system recognizer).
/// Defaults to Indonesian (`id-ID`).
class SpeechInputService {
  SpeechInputService._() {
    _channel.setMethodCallHandler(_onCall);
  }

  static final SpeechInputService instance = SpeechInputService._();
  static const String defaultLanguage = 'id-ID';

  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/SpeechInput',
  );

  void Function(String text)? _onPartial;
  void Function(String text)? _onResult;
  void Function(Object error)? _onError;
  void Function()? _onEnd;

  Future<bool> isAvailable() async {
    try {
      return (await _channel.invokeMethod<bool>('isAvailable')) == true;
    } catch (_) {
      return false;
    }
  }

  /// Starts listening. Throws [PlatformException] if unavailable or the
  /// microphone permission is denied.
  Future<void> start({
    String language = defaultLanguage,
    required void Function(String text) onPartial,
    required void Function(String text) onResult,
    required void Function(Object error) onError,
    required void Function() onEnd,
  }) async {
    _onPartial = onPartial;
    _onResult = onResult;
    _onError = onError;
    _onEnd = onEnd;
    await _channel.invokeMethod<bool>('start', {'language': language});
  }

  Future<void> stop() async {
    try {
      await _channel.invokeMethod<bool>('stop');
    } catch (_) {}
  }

  Future<void> cancel() async {
    _clearCallbacks();
    try {
      await _channel.invokeMethod<bool>('cancel');
    } catch (_) {}
  }

  void _clearCallbacks() {
    _onPartial = null;
    _onResult = null;
    _onError = null;
    _onEnd = null;
  }

  Future<dynamic> _onCall(MethodCall call) async {
    switch (call.method) {
      case 'onPartial':
        _onPartial?.call((call.arguments as String?) ?? '');
        break;
      case 'onResult':
        _onResult?.call((call.arguments as String?) ?? '');
        break;
      case 'onError':
        _onError?.call(call.arguments ?? 'unknown');
        break;
      case 'onEnd':
        final end = _onEnd;
        _clearCallbacks();
        end?.call();
        break;
    }
    return null;
  }
}
