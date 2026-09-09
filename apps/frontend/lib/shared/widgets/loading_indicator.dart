import 'package:flutter/material.dart';

/// 여러 feature가 공유하는 로딩 표시 위젯(Coding_Convention.md §6).
class LoadingIndicator extends StatelessWidget {
  const LoadingIndicator({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(child: CircularProgressIndicator());
  }
}
