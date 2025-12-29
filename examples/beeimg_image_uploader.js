/* METADATA
{
    "name": "beeimg_image_uploader_v2",
    "description": "BeeIMG（https://beeimg.com/）工具将本地图片上传到图床并返回图片url，配合图片生成工具实现图生图（图生图务必开启）。",
    "env": [
        "BEEIMG_API_KEY"
    ],
    "tools": [
        {
            "name": "upload_image",
            "description": "调用系统 Curl 工具将本地图片上传到 BeeIMG 图床。",
            "parameters": [
                { "name": "file_path", "description": "要上传的图片文件绝对路径 (建议使用 /sdcard/ 开头的完整路径)。", "type": "string", "required": true },
                { "name": "album_id", "description": "相册ID (可选)。", "type": "string", "required": false },
                { "name": "privacy", "description": "隐私设置，'public' 或 'private' (可选)。", "type": "string", "required": false }
            ]
        }
    ]
}
*/
const beeimgUploader = (function () {
    // API配置
    const API_ENDPOINT = "https://beeimg.com/api/upload/file/json/";
    let terminalSessionId = null;

    async function getTerminalSessionId() {
        if (terminalSessionId) {
            return terminalSessionId;
        }
        const session = await Tools.System.terminal.create("beeimg_uploader_session");
        terminalSessionId = session.sessionId;
        return terminalSessionId;
    }

    async function executeTerminalCommand(command) {
        const sessionId = await getTerminalSessionId();
        return await Tools.System.terminal.exec(sessionId, command);
    }

    async function checkAndInstall(toolName, packageName) {
        const checkCmd = `command -v ${toolName}`;
        const checkResult = await executeTerminalCommand(checkCmd);
        if (checkResult.exitCode === 0 && checkResult.output.trim() !== '') {
            return true;
        }
        const installCmd = `apt-get update && apt-get install -y ${packageName}`;
        const installResult = await executeTerminalCommand(installCmd);
        if (installResult.exitCode !== 0) {
            throw new Error(`无法安装依赖: ${toolName}. Output: ${installResult.output}`);
        }
        return true;
    }

    function getApiKey() {
        return getEnv("BEEIMG_API_KEY") || "";
    }

    async function upload_image(params) {
        const { file_path, album_id, privacy } = params;

        // 1. 检查文件是否存在
        const fileExists = await Tools.Files.exists(file_path);
        if (!fileExists.exists) {
            throw new Error(`文件未找到: ${file_path} (请尝试使用 /sdcard/ 开头的绝对路径)`);
        }

        // 2. 确保环境中有 curl
        await checkAndInstall("curl", "curl");

        // 3. 准备参数
        const apiKey = getApiKey();
        
        // 构建 Curl 命令
        // -s: 静默模式 (但不屏蔽错误)
        // -L: 跟随重定向 (关键修复)
        // -A: 模拟 Chrome 浏览器 (关键修复，防止 Cloudflare 拦截)
        // --fail: HTTP 错误时返回非零状态码
        let command = `curl -s -L --fail -A "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"`;
        
        // 添加表单字段
        command += ` -X POST -F "file=@${file_path}"`;
        
        if (apiKey) command += ` -F "apikey=${apiKey}"`;
        if (album_id) command += ` -F "albumid=${album_id}"`;
        if (privacy) command += ` -F "privacy=${privacy}"`;
        
        command += ` "${API_ENDPOINT}"`;

        console.log(`正在执行上传...`);
        
        // 4. 执行命令
        const result = await executeTerminalCommand(command);
        
        // 5. 处理结果
        if (result.exitCode !== 0) {
            // 如果开启了 --fail，HTTP 错误（如 403/404/500）会进入这里
            throw new Error(`上传请求失败 (Exit Code ${result.exitCode})。可能是网络问题或 IP 被屏蔽。\n终端输出: ${result.output}`);
        }

        const responseText = result.output.trim();
        
        // 如果返回为空，说明服务器没有返回任何数据
        if (!responseText) {
            throw new Error("上传失败：服务器返回了空内容。请检查网络连接或尝试关闭 VPN。");
        }

        let jsonResponse;
        try {
            // 尝试提取 JSON (为了兼容性，只提取 {} 之间的内容)
            const jsonStart = responseText.indexOf('{');
            const jsonEnd = responseText.lastIndexOf('}');
            if (jsonStart !== -1 && jsonEnd !== -1) {
                jsonResponse = JSON.parse(responseText.substring(jsonStart, jsonEnd + 1));
            } else {
                jsonResponse = JSON.parse(responseText);
            }
        } catch (e) {
            // 如果解析失败，打印出前 100 个字符以便调试
            throw new Error(`API 响应解析失败。服务器返回内容不是 JSON:\n${responseText.substring(0, 200)}...`);
        }

        // 6. 验证业务逻辑
        if (jsonResponse.files && (jsonResponse.files.status === "Success" || jsonResponse.files.code === "200")) {
            return {
                url: jsonResponse.files.url,
                thumbnail_url: jsonResponse.files.thumbnail_url,
                page_url: jsonResponse.files.view_url,
                details: `上传成功! URL: ${jsonResponse.files.url}`
            };
        } else {
            const errMsg = jsonResponse.files ? jsonResponse.files.status : "未知错误";
            throw new Error(`BeeIMG API 报错: ${errMsg}`);
        }
    }

    async function wrap(func, params) {
        try {
            const result = await func(params);
            complete({ success: true, message: "图片上传成功", data: result });
        }
        catch (error) {
            console.error(`Error: ${error.message}`);
            complete({ success: false, message: error.message, error_stack: error.stack });
        }
    }

    return {
        upload_image: (p) => wrap(upload_image, p)
    };
})();

exports.upload_image = beeimgUploader.upload_image;
