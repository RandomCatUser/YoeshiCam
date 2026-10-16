import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;

/// Live viewfinder grade: warm faded film look, grain, optional grid.
class FxOverlay extends StatelessWidget {
  const FxOverlay({super.key, required this.fx, required this.grid});

  final bool fx;
  final bool grid;

  @override
  Widget build(BuildContext context) {
    if (!fx && !grid) return const SizedBox.shrink();
    return IgnorePointer(
      child: CustomPaint(
        painter: ViewfinderPainter(fx: fx, grid: grid),
        size: Size.infinite,
      ),
    );
  }
}

class ViewfinderPainter extends CustomPainter {
  ViewfinderPainter({required this.fx, required this.grid});

  final bool fx;
  final bool grid;
  static ui.Image? _grain;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    if (grid) {
      final p = Paint()
        ..color = const Color(0x3DFFFFFF)
        ..strokeWidth = 0.7;
      for (var i = 1; i < 3; i++) {
        canvas.drawLine(
            Offset(size.width * i / 3, 0), Offset(size.width * i / 3, size.height), p);
        canvas.drawLine(
            Offset(0, size.height * i / 3), Offset(size.width, size.height * i / 3), p);
      }
    }
    if (!fx || size.isEmpty) return;

    // warm soft-light tone
    canvas.drawRect(
        rect,
        Paint()
          ..color = const Color(0x59FF9E45)
          ..blendMode = BlendMode.softLight);

    // airy highlight haze
    canvas.drawRect(
        rect,
        Paint()
          ..shader = const LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0x1EFFE3B8), Color(0x0AFFD9A6)],
          ).createShader(rect)
          ..blendMode = BlendMode.screen);

    // vignette
    final c = rect.center;
    final r = math.sqrt(c.dx * c.dx + c.dy * c.dy);
    canvas.drawRect(
        rect,
        Paint()
          ..shader = ui.Gradient.radial(
            c,
            r,
            const [Color(0x00000000), Color(0x00000000), Color(0x8C000000)],
            const [0.0, 0.55, 1.0],
          )
          ..blendMode = BlendMode.multiply);

    // film grain
    canvas.drawRect(
        rect,
        Paint()
          ..shader = ui.ImageShader(
            _grainImage(),
            TileMode.repeated,
            TileMode.repeated,
            Matrix4.identity().storage,
          )
          ..blendMode = BlendMode.overlay);
  }

  static ui.Image _grainImage() {
    if (_grain != null) return _grain!;
    const n = 128;
    final rec = ui.PictureRecorder();
    final canvas = Canvas(rec);
    final paint = Paint();
    final rng = math.Random(1999);
    for (var y = 0; y < n; y++) {
      for (var x = 0; x < n; x++) {
        final v = 40 + rng.nextInt(176);
        paint.color = Color.fromARGB(34, v, v, v);
        canvas.drawRect(Rect.fromLTWH(x.toDouble(), y.toDouble(), 1, 1), paint);
      }
    }
    _grain = rec.endRecording().toImageSync(n, n);
    return _grain!;
  }

  @override
  bool shouldRepaint(ViewfinderPainter old) => old.fx != fx || old.grid != grid;
}

/// Warm faded film grade for stills ("fly towards you" look), runs in an isolate.
Uint8List vintageGrade(Uint8List bytes) {
  final decoded = img.decodeImage(bytes);
  if (decoded == null) return bytes;
  final image = img.bakeOrientation(decoded);
  final w = image.width;
  final h = image.height;

  final lutR = _lut(1.05, 0.080);
  final lutG = _lut(1.00, 0.074);
  final lutB = _lut(0.93, 0.064);
  final cx = (w - 1) / 2;
  final cy = (h - 1) / 2;
  final invR2 = 1.0 / (cx * cx + cy * cy + 1);

  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      final p = image.getPixel(x, y);
      final r0 = p.r.toDouble();
      final g0 = p.g.toDouble();
      final b0 = p.b.toDouble();
      final lum = 0.299 * r0 + 0.587 * g0 + 0.114 * b0;
      final dx = x - cx;
      final dy = y - cy;
      final d2 = (dx * dx + dy * dy) * invR2;
      final vig = 1.0 - 0.34 * d2 * d2;
      final grain = (_hash(x, y) - 127) * 0.045 * (1.0 - 0.5 * lum / 255);
      image.setPixelRgb(
        x,
        y,
        _v(lutR[_i(lum + (r0 - lum) * 0.93)] * vig + grain),
        _v(lutG[_i(lum + (g0 - lum) * 0.93)] * vig + grain),
        _v(lutB[_i(lum + (b0 - lum) * 0.93)] * vig + grain),
      );
    }
  }

  _bloom(image);
  return img.encodeJpg(image, quality: 92);
}

// airy highlights, lifted film blacks, per channel warmth
List<int> _lut(double gain, double lift) {
  final t = List<int>.filled(256, 0);
  for (var i = 0; i < 256; i++) {
    var v = i / 255;
    v = math.pow(v, 0.93).toDouble();
    v += 0.32 * (v * v * (3 - 2 * v) - v);
    v = v * 0.88 * gain + lift;
    t[i] = (v * 255).round().clamp(0, 255).toInt();
  }
  return t;
}

void _bloom(img.Image image) {
  final w = image.width;
  final h = image.height;
  final gw = math.max(8, w ~/ 8);
  final gh = math.max(8, h ~/ 8);
  final small =
      img.copyResize(image, width: gw, height: gh, interpolation: img.Interpolation.average);
  final n = gw * gh;
  final br = Float64List(n);
  final bg = Float64List(n);
  final bb = Float64List(n);
  for (var i = 0; i < n; i++) {
    final p = small.getPixel(i % gw, i ~/ gw);
    br[i] = math.max(0.0, p.r.toDouble() - 170.0);
    bg[i] = math.max(0.0, p.g.toDouble() - 170.0);
    bb[i] = math.max(0.0, p.b.toDouble() - 170.0);
  }
  for (final a in [br, bg, bb]) {
    _boxBlur(a, gw, gh, 2);
    _boxBlur(a, gw, gh, 2);
  }
  for (var i = 0; i < n; i++) {
    small.setPixelRgb(i % gw, i ~/ gw, br[i], bg[i], bb[i]);
  }
  final glow =
      img.copyResize(small, width: w, height: h, interpolation: img.Interpolation.linear);
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      final p = image.getPixel(x, y);
      final q = glow.getPixel(x, y);
      image.setPixelRgb(
        x,
        y,
        255 - (255 - p.r) * (255 - q.r * 0.5) / 255,
        255 - (255 - p.g) * (255 - q.g * 0.5) / 255,
        255 - (255 - p.b) * (255 - q.b * 0.5) / 255,
      );
    }
  }
}

void _boxBlur(Float64List a, int w, int h, int r) {
  final t = Float64List(a.length);
  for (var y = 0; y < h; y++) {
    final o = y * w;
    var sum = 0.0;
    for (var i = -r; i <= r; i++) {
      sum += a[o + _ci(i, 0, w - 1)];
    }
    for (var x = 0; x < w; x++) {
      t[o + x] = sum / (2 * r + 1);
      sum -= a[o + _ci(x - r, 0, w - 1)];
      sum += a[o + _ci(x + r + 1, 0, w - 1)];
    }
  }
  for (var x = 0; x < w; x++) {
    var sum = 0.0;
    for (var i = -r; i <= r; i++) {
      sum += t[_ci(i, 0, h - 1) * w + x];
    }
    for (var y = 0; y < h; y++) {
      a[y * w + x] = sum / (2 * r + 1);
      sum -= t[_ci(y - r, 0, h - 1) * w + x];
      sum += t[_ci(y + r + 1, 0, h - 1) * w + x];
    }
  }
}

int _ci(int v, int lo, int hi) => v < lo ? lo : (v > hi ? hi : v);

int _i(double v) {
  if (v <= 0) return 0;
  if (v >= 255) return 255;
  return v.toInt();
}

double _v(double x) {
  if (x < 0) return 0;
  if (x > 255) return 255;
  return x;
}

int _hash(int x, int y) {
  var h = x * 374761393 + y * 668265263;
  h = (h ^ (h >> 13)) * 1274126177;
  return (h ^ (h >> 16)) & 255;
}
