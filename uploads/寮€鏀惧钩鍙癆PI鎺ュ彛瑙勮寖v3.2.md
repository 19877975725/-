# 开放平台 API 接口规范 v3.2

## 一、概述

本规范定义了公司开放平台所有 RESTful API 的通用调用方式、认证机制、错误码和限流策略。所有第三方开发者接入时必须遵守本文档约定。

## 二、通用请求规范

### 2.1 基础 URL

| 环境 | 地址 |
|------|------|
| 生产环境 | `https://open-api.example.com/v3` |
| 沙箱环境 | `https://open-api-sandbox.example.com/v3` |

### 2.2 认证方式

所有 API 请求必须携带签名和公钥信息，放在 HTTP Header 中：

```
X-Api-Key: <your_api_key>
X-Timestamp: <unix_timestamp_milliseconds>
X-Signature: <HMAC-SHA256(base64)>
X-Nonce: <random_uuid>
```

签名计算规则：

```
signing_string = HTTP_METHOD + "\n" + REQUEST_PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + BODY
signature = base64(HMAC-SHA256(api_secret, signing_string))
```

其中 `api_secret` 为开发者在开放平台申请的密钥，切勿在客户端代码中暴露。

### 2.3 请求格式

- Content-Type: `application/json; charset=utf-8`
- Accept: `application/json`
- 字符编码统一使用 UTF-8

### 2.4 分页规范

列表类接口统一使用游标分页：

| 参数 | 类型 | 说明 |
|------|------|------|
| `cursor` | string | 上一页返回的 next_cursor，首页不传 |
| `limit` | int | 每页条数，默认 20，最大 100 |

响应中包含：

```json
{
  "data": [...],
  "pagination": {
    "next_cursor": "eyJpZCI6IjEyMzQ1In0=",
    "has_more": true,
    "total": 1520
  }
}
```

## 三、核心接口

### 3.1 创建订单

```
POST /orders
```

请求体：

```json
{
  "product_id": "prod_abc123",
  "quantity": 1,
  "user_id": "user_xyz789",
  "amount": 19900,
  "currency": "CNY",
  "notify_url": "https://partner.example.com/callback",
  "metadata": {
    "source": "mini_program"
  }
}
```

响应（201 Created）：

```json
{
  "order_id": "ord_20250701_001",
  "status": "pending",
  "payment_url": "https://pay.example.com/checkout/ord_20250701_001",
  "created_at": "2025-07-01T10:30:00+08:00",
  "expires_in": 1800
}
```

订单状态流转：`pending` → `paid` → `processing` → `completed` / `cancelled` / `refunded`

### 3.2 查询订单

```
GET /orders/{order_id}
```

返回订单完整详情，含退款记录（若有）。此接口支持幂等调用，同一订单 30 秒内返回缓存结果。

### 3.3 订单退款

```
POST /orders/{order_id}/refund
```

请求体：

```json
{
  "amount": 19900,
  "reason": "用户申请退款",
  "refund_type": "full"
}
```

`refund_type` 可选值：`full`（全额退款）、`partial`（部分退款）。

## 四、限流策略

| 接口类别 | 限流规则 | 超限返回 |
|---------|---------|---------|
| 查询类 | 每分钟 120 次 / 每 AppKey | HTTP 429 |
| 写入类 | 每分钟 30 次 / 每 AppKey | HTTP 429 |
| 批量操作 | 每分钟 5 次 / 每 AppKey | HTTP 429 |

限流响应 Header 中包含：

- `X-RateLimit-Remaining`：剩余可用次数
- `X-RateLimit-Reset`：限流重置时间戳（秒）

## 五、错误码速查表

| HTTP 状态码 | 错误码 | 说明 |
|------------|--------|------|
| 400 | `INVALID_PARAM` | 请求参数校验失败，详见 message |
| 401 | `AUTH_FAILED` | 签名校验失败或 API Key 无效 |
| 403 | `PERMISSION_DENIED` | 无权限访问该资源 |
| 404 | `NOT_FOUND` | 资源不存在 |
| 409 | `CONFLICT` | 资源状态冲突（如重复创建） |
| 429 | `RATE_LIMITED` | 请求频率超限 |
| 500 | `INTERNAL_ERROR` | 服务器内部错误，请重试 |
| 503 | `SERVICE_UNAVAILABLE` | 服务暂时不可用 |

## 六、Webhook 回调

订单状态变更时，平台通过 `POST` 向开发者配置的 `notify_url` 推送通知。回调延迟通常 < 3 秒。开发者收到回调后必须返回 HTTP 200，否则平台将在 1 分钟、5 分钟、15 分钟、30 分钟、1 小时各重试一次，共 5 次。
