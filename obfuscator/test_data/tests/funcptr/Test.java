import by.radioegor146.nativeobfuscator.NotNative;

public class Test {
    @NotNative
    public static void main(String[] args) {
        System.out.println(calc(4));
    }

    public static int calc(int n) {
        int result = 0;
        for (int i = 0; i <= n; i++) {
            result += i;
        }
        return result;
    }
}
