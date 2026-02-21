package evebot.models;

public class Mission {

    public enum MissionType {
        BURNER, RECON, ANNIHILATION, BLOCKADE, ASSAULT, ENCOUNTER, OTHER
    }

    private String title;
    private String objective;
    private String target;       // faction/enemy description
    private String shipTarget;   // specific ship to destroy, e.g. "Enyo"
    private String location;
    private String security;
    private MissionType type;
    private String rawText;

    public Mission() {}

    // Getters & setters
    public String getTitle()                    { return title; }
    public void   setTitle(String title)        { this.title = title; }

    public String getObjective()                { return objective; }
    public void   setObjective(String o)        { this.objective = o; }

    public String getTarget()                   { return target; }
    public void   setTarget(String target)      { this.target = target; }

    public String getShipTarget()               { return shipTarget; }
    public void   setShipTarget(String ship)    { this.shipTarget = ship; }

    public String getLocation()                 { return location; }
    public void   setLocation(String location)  { this.location = location; }

    public String getSecurity()                 { return security; }
    public void   setSecurity(String security)  { this.security = security; }

    public MissionType getType()                { return type; }
    public void        setType(MissionType t)   { this.type = t; }

    public String getRawText()                  { return rawText; }
    public void   setRawText(String rawText)    { this.rawText = rawText; }

    @Override
    public String toString() {
        return String.format(
                "Mission{title='%s', ship='%s', location='%s', security='%s', type=%s}",
                title, shipTarget, location, security, type);
    }
}