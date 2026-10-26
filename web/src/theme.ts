import type { ThemeConfig } from 'antd';

export const workbenchTheme: ThemeConfig = {
  token: {
    colorPrimary: '#6454e8', colorInfo: '#6454e8', colorSuccess: '#159975', colorWarning: '#c68a20', colorError: '#d94f59',
    colorText: '#202638', colorTextSecondary: '#778096', colorBgLayout: '#f5f6fa', colorBorder: '#e3e6ef', colorBorderSecondary: '#edf0f5',
    borderRadius: 6, borderRadiusLG: 8, controlHeight: 32, fontSize: 14,
    fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
    boxShadowSecondary: '0 8px 32px rgba(30, 36, 64, 0.08)',
  },
  components: {
    Layout: { headerBg: '#ffffff', siderBg: '#1b2030', bodyBg: '#f5f6fa', footerBg: '#f5f6fa' },
    Menu: { darkItemBg: 'transparent', darkSubMenuItemBg: 'transparent', darkItemColor: '#aeb7cd', darkItemHoverColor: '#ffffff', darkItemSelectedBg: '#383252', darkItemSelectedColor: '#e3ddff', itemHeight: 38, itemBorderRadius: 6, iconSize: 16 },
    Table: { headerBg: '#f8f9fc', headerColor: '#778096', headerSplitColor: 'transparent', rowHoverBg: '#fafaff', cellPaddingBlock: 12, cellPaddingInline: 16, borderColor: '#edf0f5' },
    Card: { headerHeight: 48, headerFontSize: 14, paddingLG: 18 },
    Button: { primaryShadow: '0 3px 8px rgba(100, 84, 232, 0.16)', fontWeight: 500, defaultShadow: 'none' },
    Tabs: { horizontalItemGutter: 24, titleFontSize: 14 },
    Tag: { defaultBg: '#f5f6fa', defaultColor: '#667085' },
    Modal: { titleFontSize: 16 },
  },
};
