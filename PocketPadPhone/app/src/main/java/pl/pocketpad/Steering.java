package pl.pocketpad;

/** Steering-wheel angle in screen coordinates; neutral is captured after enabling. */
final class Steering {
    private boolean centered;
    private double neutral;
    private long lastSample;
    float value;
    void reset(){centered=false;value=0;lastSample=0;}
    float update(float x,float y,int rotation){
        return update(x,y,rotation,lastSample+20_000_000L);
    }
    float update(float x,float y,int rotation,long timestamp){
        double dt=lastSample==0?.02:Math.max(.001,Math.min(.05,(timestamp-lastSample)/1_000_000_000.0));
        lastSample=timestamp;
        float sx=x,sy=y;
        if(rotation==1){sx=y;sy=-x;}
        else if(rotation==2){sx=-x;sy=-y;}
        else if(rotation==3){sx=-y;sy=x;}
        if(Math.hypot(sx,sy)<2){value=0;return value;}
        // Sensor +Y points toward the top edge: clockwise wheel motion makes +X negative.
        double angle=-Math.atan2(sx,sy);
        if(!centered){neutral=angle;centered=true;}
        double delta=Math.atan2(Math.sin(angle-neutral),Math.cos(angle-neutral));
        double degrees=Math.toDegrees(delta);
        double target=Math.abs(degrees)<=3?0:Math.copySign(Math.min(1,(Math.abs(degrees)-3)/42),degrees);
        // A 10 ms time constant suppresses sensor noise without the old ~200 ms ramp.
        double alpha=1-Math.exp(-dt/.010);
        value+=(float)(alpha*(target-value));
        if(target==0 && Math.abs(value)<.01)value=0;
        return value;
    }
}
