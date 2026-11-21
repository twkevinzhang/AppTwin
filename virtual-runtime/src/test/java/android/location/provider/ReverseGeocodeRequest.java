package android.location.provider;

import java.util.Locale;

/** Test-only model of the framework request schema. */
public final class ReverseGeocodeRequest {
    private final double latitude;
    private final double longitude;
    private final int maxResults;
    private final Locale locale;
    private final int callingUid;
    private final String callingPackage;
    private final String callingAttributionTag;
    private ReverseGeocodeRequest(Builder b) {
        latitude = b.latitude;
        longitude = b.longitude;
        maxResults = b.maxResults;
        locale = b.locale;
        callingUid = b.callingUid;
        callingPackage = b.callingPackage;
        callingAttributionTag = b.callingAttributionTag;
    }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getMaxResults() { return maxResults; }
    public Locale getLocale() { return locale; }
    public int getCallingUid() { return callingUid; }
    public String getCallingPackage() { return callingPackage; }
    public String getCallingAttributionTag() { return callingAttributionTag; }
    public static final class Builder {
        private double latitude;
        private double longitude;
        private int maxResults;
        private Locale locale;
        private int callingUid;
        private String callingPackage;
        private String callingAttributionTag;
        public Builder(double latitude, double longitude, int maxResults, Locale locale, int callingUid, String callingPackage) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.maxResults = maxResults;
            this.locale = locale;
            this.callingUid = callingUid;
            this.callingPackage = callingPackage;
        }
        public Builder setCallingAttributionTag(String tag) { callingAttributionTag = tag; return this; }
        public ReverseGeocodeRequest build() { return new ReverseGeocodeRequest(this); }
    }
}
