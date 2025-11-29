package main

import (
	"fmt"
	"net/http"
	"strings"
)

func main() {
	fmt.Println("🚀 欢迎来到 Operit Go 项目！")
	fmt.Println(strings.Repeat("=", 50))
	fmt.Println("这是一个 Go 项目模板，您可以：")
	fmt.Println("  ✨ 编写和编译 Go 代码")
	fmt.Println("  📦 使用 go mod 管理依赖")
	fmt.Println("  🌐 创建高性能 Web 服务")
	fmt.Println(strings.Repeat("=", 50))

	// 示例：创建一个简单的 HTTP 服务器
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		html := `
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Operit Go</title>
    <style>
        body {
            font-family: system-ui, sans-serif;
            max-width: 800px;
            margin: 50px auto;
            padding: 20px;
            text-align: center;
        }
        h1 { color: #00ADD8; }
    </style>
</head>
<body>
    <h1>🔵 Go 服务器运行中</h1>
    <p>恭喜！您的 Operit Go 项目已成功启动。</p>
    <p>服务器运行在 http://localhost:8080</p>
</body>
</html>
		`
		fmt.Fprintf(w, html)
	})

	fmt.Println("\n✅ 服务器运行在 http://localhost:8080/")
	fmt.Println("💡 提示：修改 main.go 文件后重新构建和运行")
	
	if err := http.ListenAndServe(":8080", nil); err != nil {
		fmt.Printf("❌ 服务器错误: %v\n", err)
	}
}
