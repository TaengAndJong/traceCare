import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'secure_token_storage.dart';

final secureTokenStorageProvider = Provider<SecureTokenStorage>((ref) {
  return SecureTokenStorage();
});
