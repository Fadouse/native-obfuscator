public class IssueDivZero {
    public static void main(String[] args) {
        try {
            int v = 1 / 0;
            System.out.println("fail " + v);
        } catch (ArithmeticException e) {
            System.out.println("Caught");
        }
    }
}
