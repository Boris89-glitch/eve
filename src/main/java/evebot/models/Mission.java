// src/main/java/evebot/models/Mission.java
package evebot.models;

public class Mission {
    private String title;
    private String objective;
    private String target;
    private String location;
    private String security;
    private MissionType type;
    private String rawText;

    public enum MissionType {
        BURNER, RECON, ANNIHILATION, BLOCKADE, ASSAULT, ENCOUNTER, OTHER
    }

    // Default constructor
    public Mission() {}

    // Constructor for simple missions (legacy support)
    public Mission(String title, String objective, String rewards) {
        this.title = title;
        this.objective = objective;
        // Rewards would need to be stored somewhere if needed
        this.type = MissionType.OTHER;
    }

    // Full constructor
    public Mission(String title, String objective, String target, String location,
                   String security, MissionType type) {
        this.title = title;
        this.objective = objective;
        this.target = target;
        this.location = location;
        this.security = security;
        this.type = type;
    }

    // Getters and setters
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getObjective() { return objective; }
    public void setObjective(String objective) { this.objective = objective; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getSecurity() { return security; }
    public void setSecurity(String security) { this.security = security; }

    public MissionType getType() { return type; }
    public void setType(MissionType type) { this.type = type; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    @Override
    public String toString() {
        return String.format("Mission{title='%s', target='%s', location='%s', security='%s', type=%s}",
                title, target, location, security, type);
    }
}