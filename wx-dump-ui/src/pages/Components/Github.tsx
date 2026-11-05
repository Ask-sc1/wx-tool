import React from 'react';
import { GithubOutlined } from '@ant-design/icons';

const Github: React.FC = () => {
  return (
    <a
      href="https://github.com/Ask-sc1/wx-tool"
      target="_blank"
      rel="noopener noreferrer"
      aria-label="打开 wx-tool 的 GitHub 仓库"
      style={{
        display: 'flex',
        height: 26,
      }}
    >
      <GithubOutlined />
    </a>
  );
};

export default Github;
