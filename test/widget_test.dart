import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oldcam2011/vintage_fx.dart';

void main() {
  testWidgets('fx overlay builds', (tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(
        body: SizedBox(
          width: 200,
          height: 200,
          child: FxOverlay(fx: true, grid: true),
        ),
      ),
    ));
    expect(find.byType(CustomPaint), findsWidgets);
  });
}
