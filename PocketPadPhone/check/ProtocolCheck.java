import pl.pocketpad.PadState;
import java.util.Arrays;

public class ProtocolCheck {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        PadState state=new PadState();
        require(Arrays.equals(state.report(),new byte[]{0,0,8,0,0,0,0}),"neutral packet");
        state.buttons=(1<<0)|(1<<14);state.lx=-127;state.ry=127;
        require(Arrays.equals(state.report(),new byte[]{1,64,8,-127,0,0,127}),"button bits and signed axes");
        float[][] directions={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        for(int i=0;i<8;i++)require(PadState.hat(directions[i][0],directions[i][1])==i,"hat "+i);
        require(PadState.hat(0,0)==8,"neutral hat");
        require(PadState.axis(2)==127 && PadState.axis(-2)==-127,"axis clamping");
        int reportSize=0,reportCount=0,inputBits=0;
        byte[] d=PadState.DESCRIPTOR;
        for(int i=0;i<d.length;){int item=d[i++]&255,n=item&3;if(n==3)n=4;int value=0;for(int j=0;j<n;j++)value|=(d[i++]&255)<<(j*8);if(item==0x75)reportSize=value;if(item==0x95)reportCount=value;if(item==0x81)inputBits+=reportSize*reportCount;}
        require(inputBits==state.report().length*8,"descriptor length matches packet");
        state.clear();require(Arrays.equals(state.report(),new byte[]{0,0,8,0,0,0,0}),"release all controls");
        System.out.println("PASS: packet format, HID length, 8 directions, axes, release");
    }
}
