<?php
/**
 * FRP 版本列表 API
 * 自动扫描 aar_files 目录下的版本子目录，返回可用版本列表
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

// 处理 OPTIONS 预检请求
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

// AAR 文件存储目录（相对于当前脚本的目录）
$aarDir = __DIR__ . '/aar_files';

try {
    // 检查目录是否存在
    if (!is_dir($aarDir)) {
        http_response_code(500);
        echo json_encode([
            'code' => 500,
            'message' => 'AAR directory not found',
            'data' => []
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
    
    // 扫描版本目录
    $versions = [];
    $items = scandir($aarDir);
    
    foreach ($items as $item) {
        // 跳过 . 和 ..
        if ($item === '.' || $item === '..') {
            continue;
        }
        
        // 检查是否为目录
        $versionDir = $aarDir . '/' . $item;
        if (!is_dir($versionDir)) {
            continue;
        }
        
        // 验证版本号格式（例如：0.66.0）
        if (!preg_match('/^[0-9]+\.[0-9]+\.[0-9]+$/', $item)) {
            continue;
        }
        
        // 检查 frp.aar 文件是否存在
        $aarFile = $versionDir . '/frp.aar';
        if (!file_exists($aarFile) || !is_file($aarFile)) {
            continue;
        }
        
        // 获取文件信息
        $fileSize = filesize($aarFile);
        $fileTime = filemtime($aarFile);
        
        // 构建下载 URL（通过 download.php，它会做安全验证后重定向到静态文件）
        // 优先使用 XHost 或 X-Real-Host（反向代理设置的真实 Host），如果没有则使用 HTTP_HOST
        // 注意：XHost（无横杠）会变成 HTTP_XHOST，X-Real-Host 会变成 HTTP_X_REAL_HOST
        $host = isset($_SERVER['HTTP_XHOST']) ? $_SERVER['HTTP_XHOST'] 
            : (isset($_SERVER['HTTP_X_REAL_HOST']) ? $_SERVER['HTTP_X_REAL_HOST'] 
            : $_SERVER['HTTP_HOST']);
        $baseUrl = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on' ? 'https' : 'http')
            . '://' . $host
            . dirname($_SERVER['SCRIPT_NAME']);
        // 使用 download.php，它会验证版本号格式和文件存在性，然后重定向到静态文件
        $downloadUrl = $baseUrl . '/download.php?version=' . urlencode($item);
        
        // 添加到版本列表
        $versions[] = [
            'version' => $item,
            'url' => $downloadUrl,
            'size' => (int)$fileSize,
            'description' => 'FRP version ' . $item,
            'release_date' => date('Y-m-d', $fileTime)
        ];
    }
    
    // 按版本号排序（降序，最新的在前）
    usort($versions, function($a, $b) {
        return version_compare($b['version'], $a['version']);
    });
    
    // 返回结果
    $response = [
        'code' => 200,
        'message' => 'success',
        'data' => $versions
    ];
    
    echo json_encode($response, JSON_UNESCAPED_UNICODE);
    
} catch (Exception $e) {
    http_response_code(500);
    echo json_encode([
        'code' => 500,
        'message' => 'Server error: ' . $e->getMessage(),
        'data' => []
    ], JSON_UNESCAPED_UNICODE);
}
