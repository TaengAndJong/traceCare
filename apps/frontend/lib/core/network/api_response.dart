/// API_Response_Rule.md §7.1 그대로 구현한 공통 응답 파서.
/// 새 API를 추가할 때 개별 파서를 작성하지 않고 이 클래스를 재사용한다(재정의 금지).
class ApiResponse<T> {
  final bool success;
  final String code;
  final String message;
  final T? data;

  ApiResponse({
    required this.success,
    required this.code,
    required this.message,
    this.data,
  });

  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(dynamic) fromJsonT,
  ) {
    return ApiResponse(
      success: json['success'] as bool,
      code: json['code'] as String,
      message: json['message'] as String,
      data: json['data'] != null ? fromJsonT(json['data']) : null,
    );
  }
}
