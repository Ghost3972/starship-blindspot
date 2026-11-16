package com.elliot.blindspot;

import android.content.SharedPreferences;

import java.util.Locale;

public final class CameraLayoutConfig {
    public enum VehicleType {
        E02,
        E02_STARSHIP7_25,
        CUSTOM
    }

    public enum Direction {
        FRONT,
        BACK,
        LEFT,
        RIGHT;

        public String prefKey() {
            return name().toLowerCase(Locale.US);
        }
    }

    public enum Segment {
        A(0),
        B(1),
        C(2),
        D(3);

        private final int index;

        Segment(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }
    }

    public static final String PREF_VEHICLE_TYPE = "vehicle_type";
    public static final String PREF_OUTPUT_WIDTH = "camera_output_width";
    public static final String PREF_OUTPUT_HEIGHT = "camera_output_height";

    private static final String PREF_E02_MAP_FRONT = "e02_map_front";
    private static final String PREF_E02_MAP_BACK = "e02_map_back";
    private static final String PREF_E02_MAP_LEFT = "e02_map_left";
    private static final String PREF_E02_MAP_RIGHT = "e02_map_right";

    private static final String PREF_E02_STARSHIP7_25_MAP_FRONT = "e02_starship7_25_map_front";
    private static final String PREF_E02_STARSHIP7_25_MAP_BACK = "e02_starship7_25_map_back";
    private static final String PREF_E02_STARSHIP7_25_MAP_LEFT = "e02_starship7_25_map_left";
    private static final String PREF_E02_STARSHIP7_25_MAP_RIGHT = "e02_starship7_25_map_right";

    private static final String CUSTOM_PREFIX = "custom_rect_";
    private static final String SUFFIX_L = "_l";
    private static final String SUFFIX_T = "_t";
    private static final String SUFFIX_R = "_r";
    private static final String SUFFIX_B = "_b";

    private static final int E02_DEFAULT_W = 5120;
    private static final int E02_DEFAULT_H = 800;

    private CameraLayoutConfig() {
    }

    public static VehicleType getVehicleType(SharedPreferences prefs) {
        String raw = prefs.getString(PREF_VEHICLE_TYPE, VehicleType.E02.name());
        try {
            return VehicleType.valueOf(raw);
        } catch (Exception ignored) {
            return VehicleType.E02;
        }
    }

    public static void setVehicleType(SharedPreferences prefs, VehicleType type) {
        prefs.edit().putString(PREF_VEHICLE_TYPE, type.name()).apply();
        if (isE02Family(type)) {
            prefs.edit().putInt(PREF_OUTPUT_WIDTH, E02_DEFAULT_W).putInt(PREF_OUTPUT_HEIGHT, E02_DEFAULT_H).apply();
        }
    }

    public static int getOutputWidth(SharedPreferences prefs) {
        VehicleType type = getVehicleType(prefs);
        if (isE02Family(type)) return E02_DEFAULT_W;
        int w = prefs.getInt(PREF_OUTPUT_WIDTH, 0);
        return w > 0 ? w : E02_DEFAULT_W;
    }

    public static int getOutputHeight(SharedPreferences prefs) {
        VehicleType type = getVehicleType(prefs);
        if (isE02Family(type)) return E02_DEFAULT_H;
        int h = prefs.getInt(PREF_OUTPUT_HEIGHT, 0);
        return h > 0 ? h : E02_DEFAULT_H;
    }

    public static void updateOutputSize(SharedPreferences prefs, int width, int height) {
        if (width <= 0 || height <= 0) return;
        prefs.edit().putInt(PREF_OUTPUT_WIDTH, width).putInt(PREF_OUTPUT_HEIGHT, height).apply();
    }

    public static Segment getE02Mapping(SharedPreferences prefs, Direction direction) {
        VehicleType type = getVehicleType(prefs);
        String key = getE02Key(type, direction);
        String raw = prefs.getString(key, defaultSegmentFor(type, direction).name());
        try {
            return Segment.valueOf(raw);
        } catch (Exception ignored) {
            return defaultSegmentFor(type, direction);
        }
    }

    public static void setE02Mapping(SharedPreferences prefs, Direction direction, Segment segment) {
        VehicleType type = getVehicleType(prefs);
        prefs.edit().putString(getE02Key(type, direction), segment.name()).apply();
    }

    public static float[] getCropRectNormalized(SharedPreferences prefs, Direction direction) {
        VehicleType type = getVehicleType(prefs);
        if (isE02Family(type)) {
            Segment seg = getE02Mapping(prefs, direction);
            return segmentRect(seg);
        }
        float l = prefs.getFloat(customKey(direction, SUFFIX_L), -1f);
        float t = prefs.getFloat(customKey(direction, SUFFIX_T), -1f);
        float r = prefs.getFloat(customKey(direction, SUFFIX_R), -1f);
        float b = prefs.getFloat(customKey(direction, SUFFIX_B), -1f);
        if (l < 0f || t < 0f || r < 0f || b < 0f) {
            return segmentRect(defaultSegmentFor(type, direction));
        }
        return clampRect(l, t, r, b);
    }

    public static void setCustomCropRectNormalized(SharedPreferences prefs, Direction direction, float l, float t, float r,
            float b) {
        float[] rect = clampRect(l, t, r, b);
        prefs.edit()
                .putFloat(customKey(direction, SUFFIX_L), rect[0])
                .putFloat(customKey(direction, SUFFIX_T), rect[1])
                .putFloat(customKey(direction, SUFFIX_R), rect[2])
                .putFloat(customKey(direction, SUFFIX_B), rect[3])
                .apply();
    }

    private static String getE02Key(VehicleType type, Direction direction) {
        if (type == VehicleType.E02_STARSHIP7_25) {
            switch (direction) {
                case FRONT:
                    return PREF_E02_STARSHIP7_25_MAP_FRONT;
                case BACK:
                    return PREF_E02_STARSHIP7_25_MAP_BACK;
                case LEFT:
                    return PREF_E02_STARSHIP7_25_MAP_LEFT;
                case RIGHT:
                    return PREF_E02_STARSHIP7_25_MAP_RIGHT;
                default:
                    return PREF_E02_STARSHIP7_25_MAP_LEFT;
            }
        }
        switch (direction) {
            case FRONT:
                return PREF_E02_MAP_FRONT;
            case BACK:
                return PREF_E02_MAP_BACK;
            case LEFT:
                return PREF_E02_MAP_LEFT;
            case RIGHT:
                return PREF_E02_MAP_RIGHT;
            default:
                return PREF_E02_MAP_LEFT;
        }
    }

    private static Segment defaultSegmentFor(VehicleType type, Direction direction) {
        if (type == VehicleType.E02_STARSHIP7_25) {
            switch (direction) {
                case FRONT:
                    return Segment.C;
                case BACK:
                    return Segment.B;
                case LEFT:
                    return Segment.D;
                case RIGHT:
                    return Segment.A;
                default:
                    return Segment.C;
            }
        }
        switch (direction) {
            case FRONT:
                return Segment.A;
            case BACK:
                return Segment.B;
            case LEFT:
                return Segment.C;
            case RIGHT:
                return Segment.D;
            default:
                return Segment.A;
        }
    }

    private static boolean isE02Family(VehicleType type) {
        return type == VehicleType.E02 || type == VehicleType.E02_STARSHIP7_25;
    }

    private static String customKey(Direction direction, String suffix) {
        return CUSTOM_PREFIX + direction.prefKey() + suffix;
    }

    private static float[] segmentRect(Segment segment) {
        float step = 1f / 4f;
        float l = segment.index() * step;
        float r = l + step;
        return new float[] { l, 0f, r, 1f };
    }

    private static float[] clampRect(float l, float t, float r, float b) {
        float cl = clamp01(Math.min(l, r));
        float cr = clamp01(Math.max(l, r));
        float ct = clamp01(Math.min(t, b));
        float cb = clamp01(Math.max(t, b));
        if (cr - cl < 0.01f) cr = clamp01(cl + 0.01f);
        if (cb - ct < 0.01f) cb = clamp01(ct + 0.01f);
        return new float[] { cl, ct, cr, cb };
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
