package android.location.provider;

import java.util.Locale;

/** Test-only model of the framework request schema. */
public final class ForwardGeocodeRequest {
    private final String locationName;
    private final double lowerLeftLatitude;
    private final double lowerLeftLongitude;
    private final double upperRightLatitude;
    private final double upperRightLongitude;
    private final int maxResults;
    private final Locale locale;
    private final int callingUid;
    private final String callingPackage;
    private final String callingAttributionTag;
    private ForwardGeocodeRequest(Builder b) {
        locationName = b.locationName;
        lowerLeftLatitude = b.lowerLeftLatitude;
        lowerLeftLongitude = b.lowerLeftLongitude;
        upperRightLatitude = b.upperRightLatitude;
        upperRightLongitude = b.upperRightLongitude;
        maxResults = b.maxResults;
        locale = b.locale;
        callingUid = b.callingUid;
        callingPackage = b.callingPackage;
        callingAttributionTag = b.callingAttributionTag;
    }
    public String getLocationName() { return locationName; }
    public double getLowerLeftLatitude() { return lowerLeftLatitude; }
    public double getLowerLeftLongitude() { return lowerLeftLongitude; }
    public double getUpperRightLatitude() { return upperRightLatitude; }
    public double getUpperRightLongitude() { return upperRightLongitude; }
    public int getMaxResults() { return maxResults; }
    public Locale getLocale() { return locale; }
    public int getCallingUid() { return callingUid; }
    public String getCallingPackage() { return callingPackage; }
    public String getCallingAttributionTag() { return callingAttributionTag; }
    public static final class Builder {
        private String locationName;
        private double lowerLeftLatitude;
        private double lowerLeftLongitude;
        private double upperRightLatitude;
        private double upperRightLongitude;
        private int maxResults;
        private Locale locale;
        private int callingUid;
        private String callingPackage;
        private String callingAttributionTag;
        public Builder(String locationName, double lowerLeftLatitude, double lowerLeftLongitude, double upperRightLatitude, double upperRightLongitude, int maxResults, Locale locale, int callingUid, String callingPackage) {
            this.locationName = locationName;
            this.lowerLeftLatitude = lowerLeftLatitude;
            this.lowerLeftLongitude = lowerLeftLongitude;
            this.upperRightLatitude = upperRightLatitude;
            this.upperRightLongitude = upperRightLongitude;
            this.maxResults = maxResults;
            this.locale = locale;
            this.callingUid = callingUid;
            this.callingPackage = callingPackage;
        }
        public Builder setCallingAttributionTag(String tag) { callingAttributionTag = tag; return this; }
        public ForwardGeocodeRequest build() { return new ForwardGeocodeRequest(this); }
    }
}
