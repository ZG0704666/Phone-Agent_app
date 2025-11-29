/**
 * Operit Java 项目
 * 欢迎使用 Operit 进行 Java 开发！
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("🚀 欢迎来到 Operit Java 项目！");
        System.out.println("=" .repeat(50));
        System.out.println("这是一个 Java 项目模板，您可以：");
        System.out.println("  ✨ 编写和编译 Java 代码");
        System.out.println("  📦 使用 Gradle/Maven 管理依赖");
        System.out.println("  🏗️ 构建和运行 Java 应用");
        System.out.println("=" .repeat(50));
        
        // 示例代码
        String greeting = "Hello from Operit!";
        System.out.println("\n" + greeting + "\n");
        
        // 简单的计算示例
        int[] numbers = {1, 2, 3, 4, 5};
        int sum = 0;
        for (int num : numbers) {
            sum += num;
        }
        System.out.println("数组总和: " + sum);
    }
}
