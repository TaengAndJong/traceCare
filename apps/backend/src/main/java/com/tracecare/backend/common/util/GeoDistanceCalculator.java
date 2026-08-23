package com.tracecare.backend.common.util;

/**
 * 두 GPS 좌표 간 실제 지표면 거리를 Haversine 공식으로 계산하는 공용 유틸. 위도에 따라 경도 1도가 가리키는 실제 거리가 달라지므로 단순 좌표 차이 비교로는
 * 부정확하다. Place 중복 판정(반경 50m)뿐 아니라 향후 Location/ArrivalCheck 도메인(GeoFence 도착 판정)에서도 동일한 거리 계산이 필요할
 * 가능성이 높아 도메인에 가두지 않고 {@code common}에 둔다(Coding_Convention.md §1.3 "도메인 이름을 몰라도 이해되는 코드만 common에
 * 둔다").
 */
public final class GeoDistanceCalculator {

    /** 지구 평균 반지름(m). */
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoDistanceCalculator() {}

    /** 두 좌표(위도/경도, 십진도) 간 실제 거리를 미터 단위로 반환한다. */
    public static double distanceInMeters(
            double latitude1, double longitude1, double latitude2, double longitude2) {
        double lat1Rad = Math.toRadians(latitude1);
        double lat2Rad = Math.toRadians(latitude2);
        double deltaLatRad = Math.toRadians(latitude2 - latitude1);
        double deltaLngRad = Math.toRadians(longitude2 - longitude1);

        double a =
                Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2)
                        + Math.cos(lat1Rad)
                                * Math.cos(lat2Rad)
                                * Math.sin(deltaLngRad / 2)
                                * Math.sin(deltaLngRad / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METERS * c;
    }
}
