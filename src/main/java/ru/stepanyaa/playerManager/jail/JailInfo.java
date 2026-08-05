package ru.stepanyaa.playerManager.jail;

public class JailInfo {

    private final String name;
    private final String displayName;

    public JailInfo(String name) {
        this(name, name);
    }

    public JailInfo(String name, String displayName) {
        this.name = name;
        this.displayName = (displayName == null || displayName.isEmpty()) ? name : displayName;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return name;
    }
}
