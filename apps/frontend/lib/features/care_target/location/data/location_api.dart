import 'package:dio/dio.dart';

import '../../../../core/network/api_response.dart';

class LocationSendResult {
  final int locationId;
  final DateTime recordedAt;

  LocationSendResult({required this.locationId, required this.recordedAt});

  factory LocationSendResult.fromJson(Map<String, dynamic> json) =>
      LocationSendResult(
        locationId: json['locationId'] as int,
        recordedAt: DateTime.parse(json['recordedAt'] as String),
      );
}

/// `POST /api/care-target/location`(API_Specification.md §4.1) 호출부.
class LocationApi {
  LocationApi(this._dio);

  final Dio _dio;

  /// [latitude]/[longitude]는 GPS 원본 정밀도를 그대로 전달한다(API_Response_Rule.md §1.4 —
  /// 소수점 6자리 이상 유지, 서버 전송 전 임의 반올림 금지). [recordedAt]은 UTC로 변환해 전송한다.
  Future<LocationSendResult> sendLocation({
    required double latitude,
    required double longitude,
    required DateTime recordedAt,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/care-target/location',
      data: {
        'latitude': latitude,
        'longitude': longitude,
        'recordedAt': recordedAt.toUtc().toIso8601String(),
      },
    );
    final body = ApiResponse<LocationSendResult>.fromJson(
      response.data!,
      (json) => LocationSendResult.fromJson(json as Map<String, dynamic>),
    );
    return body.data!;
  }
}
