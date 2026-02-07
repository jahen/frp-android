<?php
/**
 * FRP AAR 文件下载 API
 * 根据版本号下载对应的 AAR 文件
 */

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

// 处理 OPTIONS 预检请求
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

// 获取版本号
$version = isset($_GET['version']) ? trim($_GET['version']) : '';

if (empty($version)) {
    http_response_code(400);
    echo json_encode([
        'code' => 400,
        'message' => 'Version parameter is required'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// 验证版本号格式（防止路径遍历攻击）
if (!preg_match('/^[0-9]+\.[0-9]+\.[0-9]+$/', $version)) {
    http_response_code(400);
    echo json_encode([
        'code' => 400,
        'message' => 'Invalid version format'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// AAR 文件存储目录（相对于当前脚本的目录）
$aarDir = __DIR__ . '/aar_files';
$aarFile = $aarDir . '/' . $version . '/frp.aar';

// 检查文件是否存在
if (!file_exists($aarFile)) {
    http_response_code(404);
    echo json_encode([
        'code' => 404,
        'message' => 'Version not found'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// 构建直接文件 URL，重定向到静态文件，让 Web 服务器直接处理，速度更快
// 优先使用 XHost 或 X-Real-Host（反向代理设置的真实 Host），如果没有则使用 HTTP_HOST
// 注意：XHost（无横杠）会变成 HTTP_XHOST，X-Real-Host 会变成 HTTP_X_REAL_HOST
$host = isset($_SERVER['HTTP_XHOST']) ? $_SERVER['HTTP_XHOST'] 
    : (isset($_SERVER['HTTP_X_REAL_HOST']) ? $_SERVER['HTTP_X_REAL_HOST'] 
    : $_SERVER['HTTP_HOST']);
$baseUrl = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on' ? 'https' : 'http')
    . '://' . $host
    . dirname($_SERVER['SCRIPT_NAME']);
$fileUrl = $baseUrl . '/aar_files/' . urlencode($version) . '/frp.aar';

// 302 重定向到实际文件，让客户端直接下载静态文件
header('Location: ' . $fileUrl);
exit;
