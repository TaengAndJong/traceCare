import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../application/auth_controller.dart';

/// Role 확정(최초 온보딩 전용, 최초 1회만 노출 — 이후 재노출하지 않는다, API_Specification.md §2.2 `USER_004`).
///
/// 이번 Phase는 CareTarget 화면만 존재하므로 Role 선택지를 CareTarget으로 좁혔다(§5 판단, 결과 보고
/// 참고) — Backend API(`PUT /api/auth/role`) 자체는 `GUARDIAN`도 받지만, 이 앱에 Guardian 화면이 아직
/// 없어 선택 UI에 노출해도 이후 진행할 화면이 없기 때문이다.
class RoleSelectScreen extends ConsumerStatefulWidget {
  const RoleSelectScreen({super.key});

  @override
  ConsumerState<RoleSelectScreen> createState() => _RoleSelectScreenState();
}

class _RoleSelectScreenState extends ConsumerState<RoleSelectScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  DateTime? _birthDate;

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authControllerProvider);

    ref.listen(authControllerProvider, (previous, next) {
      final message = next.errorMessage;
      if (message != null) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message)));
      }
    });

    return Scaffold(
      appBar: AppBar(title: const Text('보호대상자 정보 등록')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _nameController,
                decoration: const InputDecoration(labelText: '이름'),
                validator: (value) => (value == null || value.trim().isEmpty)
                    ? '이름을 입력해주세요'
                    : null,
              ),
              const SizedBox(height: 16),
              _BirthDatePicker(
                birthDate: _birthDate,
                onPick: (picked) => setState(() => _birthDate = picked),
              ),
              const SizedBox(height: 32),
              if (authState.isLoading)
                const Center(child: CircularProgressIndicator())
              else
                ElevatedButton(
                  onPressed: _submit,
                  child: const Text('CareTarget으로 시작하기'),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (_birthDate == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('생년월일을 선택해주세요')));
      return;
    }

    final birthDate =
        '${_birthDate!.year.toString().padLeft(4, '0')}-'
        '${_birthDate!.month.toString().padLeft(2, '0')}-'
        '${_birthDate!.day.toString().padLeft(2, '0')}';

    final success = await ref
        .read(authControllerProvider.notifier)
        .confirmCareTargetRole(
          name: _nameController.text.trim(),
          birthDate: birthDate,
        );

    if (success && mounted) {
      Navigator.of(context).pushReplacementNamed('/location');
    }
  }
}

class _BirthDatePicker extends StatelessWidget {
  const _BirthDatePicker({required this.birthDate, required this.onPick});

  final DateTime? birthDate;
  final ValueChanged<DateTime> onPick;

  @override
  Widget build(BuildContext context) {
    final label = birthDate == null
        ? '생년월일 선택'
        : '${birthDate!.year}-${birthDate!.month.toString().padLeft(2, '0')}-'
              '${birthDate!.day.toString().padLeft(2, '0')}';

    return OutlinedButton(
      onPressed: () async {
        final picked = await showDatePicker(
          context: context,
          initialDate: DateTime(2000),
          firstDate: DateTime(1900),
          lastDate: DateTime.now(),
        );
        if (picked != null) onPick(picked);
      },
      child: Text(label),
    );
  }
}
