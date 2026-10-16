import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart' show compute;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:gal/gal.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:device_info_plus/device_info_plus.dart';

import 'vintage_fx.dart';

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  final _vfKey = GlobalKey();
  final _previewKey = GlobalKey();

  List<CameraDescription> _cameras = [];
  CameraController? _controller;
  bool _ready = false;
  bool _busy = false;
  bool _isVideo = false;
  bool _recording = false;
  bool _fx = true;
  bool _grid = false;
  FlashMode _flash = FlashMode.auto;
  bool _hasMicPerm = false;

  String? _status;
  bool _statusSticky = false;
  Timer? _statusTimer;

  String? _lastPath;
  bool _lastIsVideo = false;

  Offset? _focusPos;
  int _focusTick = 0;
  bool _focusShown = false;

  Timer? _recTimer;
  int _seconds = 0;
  bool _blink = true;
  bool _shutterDown = false;

  @override
  void initState() {
    super.initState();
    WakelockPlus.enable();
    _init();
  }

  @override
  void dispose() {
    WakelockPlus.disable();
    _recTimer?.cancel();
    _statusTimer?.cancel();
    _controller?.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    await _checkPerms();
    try {
      _cameras = await availableCameras();
    } catch (_) {
      _cameras = [];
    }
    if (_cameras.isEmpty) {
      _setStatus('NO CAMERA FOUND', sticky: true);
      return;
    }
    await _start(_cameras.first);
  }

  Future<void> _checkPerms() async {
    final cam = await Permission.camera.request();
    final mic = await Permission.microphone.request();
    _hasMicPerm = mic.isGranted;

    var extOk = true;
    if (Platform.isAndroid) {
      final info = await DeviceInfoPlugin().androidInfo;
      if (info.version.sdkInt <= 29) {
        extOk = (await Permission.storage.request()).isGranted;
      }
    }
    if (!cam.isGranted) {
      _setStatus('CAMERA PERMISSION NEEDED', sticky: true);
    } else if (!extOk) {
      _setStatus('STORAGE PERMISSION NEEDED', sticky: true);
    }
  }

  Future<void> _start(CameraDescription desc) async {
    final ctrl = CameraController(
      desc,
      ResolutionPreset.high,
      enableAudio: true,
      imageFormatGroup: ImageFormatGroup.jpeg,
    );
    try {
      await ctrl.initialize();
      await ctrl.setFocusMode(FocusMode.auto);
      await ctrl.setExposureMode(ExposureMode.auto);
      await ctrl.lockCaptureOrientation(DeviceOrientation.portraitUp);
      if (_flash != FlashMode.auto) await ctrl.setFlashMode(_flash);
    } catch (_) {
      await ctrl.dispose();
      if (mounted) _setStatus('CAMERA INIT FAILED', sticky: true);
      return;
    }
    if (!mounted) {
      await ctrl.dispose();
      return;
    }
    setState(() {
      _controller = ctrl;
      _ready = true;
    });
  }

  Future<void> _switchCam() async {
    if (_cameras.length < 2 || _busy || _recording) return;
    setState(() => _busy = true);
    final cur = _controller?.description;
    final next =
        _cameras.firstWhere((c) => c != cur, orElse: () => _cameras.first);
    await _controller?.dispose();
    if (mounted) {
      setState(() {
        _controller = null;
        _ready = false;
      });
    }
    await _start(next);
    if (mounted) setState(() => _busy = false);
  }

  void _setMode(bool video) {
    if (_recording || _busy || video == _isVideo) return;
    setState(() => _isVideo = video);
    _setStatus(video ? 'VIDEO MODE' : 'PHOTO MODE');
  }

  void _cycleFlash() {
    if (_recording || _busy) return;
    final next = switch (_flash) {
      FlashMode.auto => FlashMode.always,
      FlashMode.always => FlashMode.off,
      _ => FlashMode.auto,
    };
    setState(() => _flash = next);
    try {
      _controller?.setFlashMode(next);
    } catch (_) {}
    _setStatus(switch (next) {
      FlashMode.auto => 'FLASH AUTO',
      FlashMode.always => 'FLASH ON',
      _ => 'FLASH OFF',
    });
  }

  Future<void> _shutter() async {
    if (!_ready || _controller == null || _busy) return;
    if (_isVideo) {
      if (_recording) {
        await _stopRec();
      } else {
        await _startRec();
      }
    } else {
      await _takePic();
    }
  }

  Future<void> _takePic() async {
    setState(() => _busy = true);
    try {
      final x = await _controller!.takePicture();
      final bytes = await x.readAsBytes();
      Uint8List out = bytes;
      if (_fx) {
        _setStatus('DEVELOPING\u2026');
        out = await compute(vintageGrade, bytes);
      } else {
        _setStatus('SAVING\u2026');
      }
      final path = await _writeShot(out, 'IMG');
      await Gal.putImage(path, album: 'Camera');
      if (!mounted) return;
      setState(() {
        _lastPath = path;
        _lastIsVideo = false;
      });
      _setStatus('SAVED \u00B7 DCIM/CAMERA');
    } catch (_) {
      _setStatus('SAVE FAILED');
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _startRec() async {
    if (!_hasMicPerm) {
      final r = await Permission.microphone.request();
      if (!r.isGranted) {
        _setStatus('MIC PERMISSION NEEDED');
        return;
      }
      _hasMicPerm = true;
    }
    setState(() => _busy = true);
    try {
      await _controller!.startVideoRecording();
      if (!mounted) return;
      setState(() {
        _recording = true;
        _seconds = 0;
        _blink = true;
      });
      _recTimer = Timer.periodic(const Duration(seconds: 1), (_) {
        if (!mounted) return;
        setState(() {
          _seconds++;
          _blink = !_blink;
        });
      });
    } catch (_) {
      _setStatus('REC START FAILED');
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _stopRec() async {
    setState(() => _busy = true);
    _recTimer?.cancel();
    try {
      final x = await _controller!.stopVideoRecording();
      _setStatus('SAVING VIDEO\u2026');
      await Gal.putVideo(x.path, album: 'Camera');
      if (!mounted) return;
      setState(() {
        _recording = false;
        _lastPath = x.path;
        _lastIsVideo = true;
      });
      _setStatus('SAVED \u00B7 DCIM/CAMERA');
    } catch (_) {
      if (mounted) setState(() => _recording = false);
      _setStatus('SAVE FAILED');
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<String> _writeShot(Uint8List bytes, String prefix) async {
    final dir = await getTemporaryDirectory();
    final d = DateTime.now();
    String n(int v) => v.toString().padLeft(2, '0');
    final name =
        '${prefix}_${d.year}${n(d.month)}${n(d.day)}_${n(d.hour)}${n(d.minute)}${n(d.second)}.jpg';
    final f = File(p.join(dir.path, name));
    await f.writeAsBytes(bytes, flush: true);
    return f.path;
  }

  void _setStatus(String m, {bool sticky = false}) {
    _statusTimer?.cancel();
    if (!mounted) return;
    setState(() {
      _status = m;
      _statusSticky = sticky;
    });
    if (!sticky) {
      _statusTimer = Timer(const Duration(milliseconds: 1700), () {
        if (mounted) setState(() => _status = null);
      });
    }
  }

  void _onViewfinderTap(TapUpDetails d) {
    final ctrl = _controller;
    final pctx = _previewKey.currentContext;
    final vctx = _vfKey.currentContext;
    if (ctrl == null || pctx == null || vctx == null) return;
    final pbox = pctx.findRenderObject() as RenderBox;
    final vbox = vctx.findRenderObject() as RenderBox;
    final lp = pbox.globalToLocal(d.globalPosition);
    final norm = Offset(
      (lp.dx / pbox.size.width).clamp(0.0, 1.0),
      (lp.dy / pbox.size.height).clamp(0.0, 1.0),
    );
    ctrl.setFocusPoint(norm);
    ctrl.setExposurePoint(norm);
    final tick = ++_focusTick;
    setState(() {
      _focusPos = vbox.globalToLocal(d.globalPosition);
      _focusShown = true;
    });
    Future.delayed(const Duration(milliseconds: 1100), () {
      if (mounted && tick == _focusTick) setState(() => _focusShown = false);
    });
  }

  void _openLast() {
    final path = _lastPath;
    if (path == null) {
      _setStatus('NO PICTURES YET');
      return;
    }
    showDialog<void>(
      context: context,
      barrierColor: Colors.black87,
      builder: (ctx) => GestureDetector(
        onTap: () => Navigator.of(ctx).pop(),
        child: Scaffold(
          backgroundColor: Colors.transparent,
          body: SafeArea(
            child: Stack(children: [
              Center(
                child: _lastIsVideo
                    ? const Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.videocam,
                              size: 54, color: Colors.white54),
                          SizedBox(height: 10),
                          Text('VIDEO \u00B7 DCIM/CAMERA',
                              style: TextStyle(
                                  fontSize: 11,
                                  letterSpacing: 2,
                                  color: Colors.white70)),
                        ],
                      )
                    : InteractiveViewer(
                        child: Image.file(File(path), fit: BoxFit.contain)),
              ),
              const Positioned(
                top: 12,
                left: 0,
                right: 0,
                child: Center(
                  child: Text('TAP TO CLOSE',
                      style: TextStyle(
                          fontSize: 9,
                          letterSpacing: 2,
                          color: Colors.white38)),
                ),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  IconData _flashIcon() => switch (_flash) {
        FlashMode.auto => Icons.flash_auto,
        FlashMode.always => Icons.flash_on,
        _ => Icons.flash_off,
      };

  String _fmtTime(int s) {
    String n(int v) => v.toString().padLeft(2, '0');
    return '${n(s ~/ 60)}:${n(s % 60)}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF14100C),
      body: SafeArea(
        child: Column(children: [
          _buildTopBar(),
          Expanded(child: _buildViewfinder()),
          _buildDeck(context),
        ]),
      ),
    );
  }

  Widget _buildTopBar() {
    return Container(
      height: 46,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color(0xFF6E6E73),
            Color(0xFF4C4C51),
            Color(0xFF35353A),
            Color(0xFF434348),
          ],
          stops: [0.0, 0.25, 0.7, 1.0],
        ),
        border: Border(bottom: BorderSide(color: Colors.black87)),
      ),
      child: Row(children: [
        Opacity(
          opacity: _recording ? 0.4 : 1,
          child: _RoundBtn(icon: _flashIcon(), active: _flash == FlashMode.always, onTap: _cycleFlash),
        ),
        const Spacer(),
        if (_recording)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 4),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.55),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: Colors.black54),
            ),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              AnimatedOpacity(
                opacity: _blink ? 1 : 0.15,
                duration: const Duration(milliseconds: 420),
                child: Container(
                  width: 8,
                  height: 8,
                  decoration: const BoxDecoration(
                      color: Color(0xFFFF3B30), shape: BoxShape.circle),
                ),
              ),
              const SizedBox(width: 7),
              Text(_fmtTime(_seconds),
                  style: const TextStyle(
                      fontSize: 11,
                      letterSpacing: 1.5,
                      fontWeight: FontWeight.w700,
                      color: Colors.white)),
            ]),
          )
        else
          const Text(
            'OLD CAM 2011',
            style: TextStyle(
                fontSize: 9,
                letterSpacing: 2.6,
                fontWeight: FontWeight.w700,
                color: Color(0xFFBEBEC3),
                shadows: [Shadow(color: Colors.black54, offset: Offset(0, 1))]),
          ),
        const Spacer(),
        _RoundBtn(
            icon: _grid ? Icons.grid_on : Icons.grid_off,
            active: _grid,
            onTap: () => setState(() => _grid = !_grid)),
        const SizedBox(width: 8),
        Opacity(
          opacity: (_busy || _recording) ? 0.4 : 1,
          child: _RoundBtn(icon: Icons.flip_camera_ios, onTap: _switchCam),
        ),
      ]),
    );
  }

  Widget _buildViewfinder() {
    return Container(
      color: Colors.black,
      child: Stack(key: _vfKey, children: [
        if (_ready && _controller != null)
          GestureDetector(
            key: _previewKey,
            behavior: HitTestBehavior.opaque,
            onTapUp: _onViewfinderTap,
            child: CameraPreview(_controller!),
          )
        else
          const Center(
            child: SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(
                  strokeWidth: 2, color: Color(0xFF8A8A90)),
            ),
          ),
        Positioned.fill(child: FxOverlay(fx: _fx, grid: _grid)),
        if (_focusPos != null)
          Positioned(
            left: _focusPos!.dx - 36,
            top: _focusPos!.dy - 36,
            child: IgnorePointer(
              child: AnimatedOpacity(
                key: ValueKey(_focusTick),
                opacity: _focusShown ? 1 : 0,
                duration: const Duration(milliseconds: 450),
                child: AnimatedScale(
                  key: ValueKey(_focusTick),
                  scale: _focusShown ? 1 : 1.45,
                  duration: const Duration(milliseconds: 320),
                  curve: Curves.easeOutCubic,
                  child: const _FocusBox(),
                ),
              ),
            ),
          ),
        Align(
          alignment: Alignment.bottomCenter,
          child: Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 180),
              transitionBuilder: (child, anim) => FadeTransition(
                opacity: anim,
                child: SlideTransition(
                  position:
                      Tween(begin: const Offset(0, 0.4), end: Offset.zero)
                          .animate(anim),
                  child: child,
                ),
              ),
              child: _status == null
                  ? const SizedBox.shrink(key: ValueKey('none'))
                  : Container(
                      key: ValueKey(_status),
                      padding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 6),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.62),
                        borderRadius: BorderRadius.circular(4),
                        border: Border.all(color: Colors.white24),
                      ),
                      child: Text(
                        _status!,
                        style: TextStyle(
                            fontSize: 10,
                            letterSpacing: 2,
                            fontWeight: FontWeight.w700,
                            color: _statusSticky
                                ? const Color(0xFFFFB4A8)
                                : const Color(0xFFEDE3C9)),
                      ),
                    ),
            ),
          ),
        ),
      ]),
    );
  }

  Widget _buildDeck(BuildContext context) {
    return Container(
      padding:
          EdgeInsets.only(bottom: MediaQuery.viewPaddingOf(context).bottom),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color(0xFF77777C),
            Color(0xFF4E4E53),
            Color(0xFF333338),
            Color(0xFF414146),
          ],
          stops: [0.0, 0.18, 0.75, 1.0],
        ),
        border: Border(top: BorderSide(color: Color(0xFFA8A8AD))),
        boxShadow: [
          BoxShadow(color: Colors.black54, blurRadius: 8, spreadRadius: 1),
        ],
      ),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const SizedBox(height: 12),
        _ModeSwitch(isVideo: _isVideo, onChanged: _setMode),
        const SizedBox(height: 12),
        Row(children: [
          const SizedBox(width: 16),
          _ThumbButton(
              path: _lastPath, isVideo: _lastIsVideo, onTap: _openLast),
          const Spacer(),
          Opacity(
            opacity: _busy ? 0.5 : 1,
            child: GestureDetector(
              onTapDown: (_) => setState(() => _shutterDown = true),
              onTapUp: (_) => setState(() => _shutterDown = false),
              onTapCancel: () => setState(() => _shutterDown = false),
              onTap: _shutter,
              child: AnimatedScale(
                scale: _shutterDown ? 0.93 : 1,
                duration: const Duration(milliseconds: 80),
                child: _buildShutter(),
              ),
            ),
          ),
          const Spacer(),
          _FxButton(on: _fx, onTap: () {
            setState(() => _fx = !_fx);
            _setStatus(_fx ? 'VINTAGE FX ON' : 'VINTAGE FX OFF');
          }),
          const SizedBox(width: 16),
        ]),
        const SizedBox(height: 8),
        const Text(
          'OLD CAM 2011 \u00B7 12.1 MEGA PIXELS',
          style: TextStyle(
              fontSize: 7.5,
              letterSpacing: 1.8,
              fontWeight: FontWeight.w700,
              color: Color(0xFF9C9CA1),
              shadows: [Shadow(color: Colors.black54, offset: Offset(0, 1))]),
        ),
        const SizedBox(height: 7),
      ]),
    );
  }

  Widget _buildShutter() {
    return Container(
      width: 82,
      height: 82,
      padding: const EdgeInsets.all(5),
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFF0F0F3), Color(0xFFC0C0C5), Color(0xFF8C8C92)],
        ),
        border: Border.all(color: const Color(0xFF26262A), width: 2),
        boxShadow: const [
          BoxShadow(color: Colors.black54, blurRadius: 7, offset: Offset(0, 3)),
        ],
      ),
      child: Container(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: _recording
              ? const RadialGradient(
                  center: Alignment(0, -0.5),
                  colors: [Color(0xFFD0453A), Color(0xFFA81C11)],
                )
              : _isVideo
                  ? const RadialGradient(
                      center: Alignment(0, -0.5),
                      colors: [Color(0xFFFF6F63), Color(0xFFE0392B), Color(0xFFA81C11)],
                    )
                  : const RadialGradient(
                      center: Alignment(0, -0.55),
                      colors: [Color(0xFFFFFFFF), Color(0xFFEFEFF2), Color(0xFFC9C9CE)],
                    ),
          border: Border.all(color: Colors.black26),
        ),
        child: _recording
            ? Center(
                child: Container(
                  width: 23,
                  height: 23,
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(3),
                    boxShadow: [
                      BoxShadow(color: Colors.black.withValues(alpha: 0.35)),
                    ],
                  ),
                ),
              )
            : null,
      ),
    );
  }
}

class _RoundBtn extends StatelessWidget {
  const _RoundBtn({required this.icon, required this.onTap, this.active = false});

  final IconData icon;
  final VoidCallback onTap;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 34,
        height: 34,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: const RadialGradient(
            center: Alignment(0, -0.55),
            radius: 1.1,
            colors: [Color(0xFFF2F2F4), Color(0xFFC9C9CE), Color(0xFF9C9CA2)],
          ),
          border: Border.all(color: Colors.black54),
          boxShadow: const [
            BoxShadow(color: Colors.black45, blurRadius: 3, offset: Offset(0, 1)),
          ],
        ),
        child: Icon(icon,
            size: 17,
            color: active ? const Color(0xFF1E6FD9) : const Color(0xFF3A3A3E)),
      ),
    );
  }
}

class _ModeSwitch extends StatelessWidget {
  const _ModeSwitch({required this.isVideo, required this.onChanged});

  final bool isVideo;
  final ValueChanged<bool> onChanged;

  static const _w = 180.0;
  static const _h = 30.0;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapUp: (d) => onChanged(d.localPosition.dx > _w / 2),
      child: SizedBox(
        width: _w,
        height: _h,
        child: Stack(children: [
          Positioned.fill(
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(6),
                gradient: const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0xFF2A2A2E), Color(0xFF1A1A1E), Color(0xFF242428)],
                ),
                border: Border.all(color: Colors.black),
              ),
            ),
          ),
          AnimatedPositioned(
            duration: const Duration(milliseconds: 230),
            curve: Curves.easeOutCubic,
            left: isVideo ? _w / 2 : 0,
            top: 2,
            width: _w / 2,
            height: _h - 4,
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(5),
                gradient: const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0xFFFAFAFC),
                    Color(0xFFD8D8DC),
                    Color(0xFFB8B8BD),
                    Color(0xFFD5D5D9),
                  ],
                ),
                border: Border.all(color: const Color(0xFF75757B)),
                boxShadow: const [
                  BoxShadow(color: Colors.black38, blurRadius: 2, offset: Offset(0, 1)),
                ],
              ),
            ),
          ),
          Positioned.fill(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(children: [
                Expanded(
                    child: Center(
                        child: Text('PHOTO',
                            style: _label(!isVideo)))),
                Expanded(
                    child: Center(
                        child: Text('VIDEO',
                            style: _label(isVideo)))),
              ]),
            ),
          ),
        ]),
      ),
    );
  }

  TextStyle _label(bool sel) => TextStyle(
        fontSize: 10,
        letterSpacing: 2.2,
        fontWeight: FontWeight.w800,
        color: sel ? const Color(0xFF2C2C30) : const Color(0xFF9A9AA0),
      );
}

class _ThumbButton extends StatelessWidget {
  const _ThumbButton({required this.path, required this.isVideo, required this.onTap});

  final String? path;
  final bool isVideo;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 46,
        height: 46,
        padding: const EdgeInsets.all(2.5),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(6),
          gradient: const LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFFE6E6EA), Color(0xFFA6A6AC)],
          ),
          border: Border.all(color: Colors.black54),
          boxShadow: const [
            BoxShadow(color: Colors.black45, blurRadius: 4, offset: Offset(0, 2)),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(3.5),
          child: path == null
              ? Container(
                  color: const Color(0xFF1C1C20),
                  child: const Icon(Icons.photo_camera,
                      size: 17, color: Color(0xFF77777E)),
                )
              : isVideo
                  ? Container(
                      color: const Color(0xFF1C1C20),
                      child: const Icon(Icons.videocam,
                          size: 17, color: Color(0xFFB9B9BE)),
                    )
                  : Image.file(
                      File(path!),
                      fit: BoxFit.cover,
                      cacheWidth: 96,
                      errorBuilder: (_, __, ___) => Container(
                        color: const Color(0xFF1C1C20),
                        child: const Icon(Icons.broken_image,
                            size: 17, color: Color(0xFF77777E)),
                      ),
                    ),
        ),
      ),
    );
  }
}

class _FxButton extends StatelessWidget {
  const _FxButton({required this.on, required this.onTap});

  final bool on;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        width: 56,
        height: 46,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(6),
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: on
                ? const [Color(0xFFFFE9AE), Color(0xFFF2C769), Color(0xFFD9A63F)]
                : const [Color(0xFFE6E6EA), Color(0xFFB4B4BA), Color(0xFF96969C)],
          ),
          border: Border.all(color: Colors.black54),
          boxShadow: const [
            BoxShadow(color: Colors.black45, blurRadius: 4, offset: Offset(0, 2)),
          ],
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.filter_vintage,
                size: 17, color: on ? const Color(0xFF5A3B10) : const Color(0xFF4A4A50)),
            const SizedBox(height: 2),
            Text('VINTAGE',
                style: TextStyle(
                    fontSize: 7,
                    letterSpacing: 0.8,
                    fontWeight: FontWeight.w800,
                    color: on ? const Color(0xFF5A3B10) : const Color(0xFF4A4A50))),
          ],
        ),
      ),
    );
  }
}

class _FocusBox extends StatelessWidget {
  const _FocusBox();

  @override
  Widget build(BuildContext context) =>
      CustomPaint(size: const Size(72, 72), painter: _FocusPainter());
}

class _FocusPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final r = Rect.fromLTWH(1, 1, size.width - 2, size.height - 2);
    canvas.drawRect(
        r,
        Paint()
          ..color = const Color(0x66FFD064)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1);
    const len = 14.0;
    final corner = Paint()
      ..color = const Color(0xFFFFD064)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round;
    final tl = r.topLeft;
    final tr = r.topRight;
    final bl = r.bottomLeft;
    final br = r.bottomRight;
    canvas.drawLine(tl, tl + const Offset(len, 0), corner);
    canvas.drawLine(tl, tl + const Offset(0, len), corner);
    canvas.drawLine(tr, tr + const Offset(-len, 0), corner);
    canvas.drawLine(tr, tr + const Offset(0, len), corner);
    canvas.drawLine(bl, bl + const Offset(len, 0), corner);
    canvas.drawLine(bl, bl + const Offset(0, -len), corner);
    canvas.drawLine(br, br + const Offset(-len, 0), corner);
    canvas.drawLine(br, br + const Offset(0, -len), corner);
  }

  @override
  bool shouldRepaint(_FocusPainter oldDelegate) => false;
}
