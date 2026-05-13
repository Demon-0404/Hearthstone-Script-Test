---
name: project-hs-script
description: 炉石传说自动化脚本项目的策略插件开发上下文，重点在铺场德（Token Druid）策略
metadata:
  type: project
---

## 项目信息
- **路径**: `E:\DATA\HearthStone_Script\Hearthstone-Script`
- **类型**: Java + Maven 多模块项目
- **核心框架**: hs-script-base, hs-script-plugin-sdk, hs-script-strategy-sdk

## 铺场德策略（Token Druid）
此前在此项目中开发过铺场德鲁伊的自动化策略脚本。卡组核心思路是通过快速召唤大量低费随从（token）占据场面，利用群体 buff 或直伤取胜。

### 关键模块
- `hs-script-strategy-sdk`: 策略插件 SDK
- `hs-strategy-plugin-template`: 策略插件模板
- `user-strategy-plugins`: 用户自定义策略存放目录

### 状态
- [ ] 需要确认现有铺场德代码位置
- [ ] 可能涉及策略逻辑优化或新卡适配
