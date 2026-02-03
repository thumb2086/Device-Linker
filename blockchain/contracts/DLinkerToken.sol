// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "@openzeppelin/contracts/access/Ownable.sol";

/**
 * @title DLinkerToken
 * @dev 專為 D-Linker 設備綁定設計的代幣 (Remix 部署版本)。
 */
contract DLinkerToken is ERC20, Ownable {
    constructor() ERC20("D-Linker Token", "DLINK") Ownable(msg.sender) {
        // 部署時可選：自動 mint 一些給自己測試
        // _mint(msg.sender, 1000000 * 10 ** decimals()); // 100萬顆
    }

    // 管理員 mint 給新用戶（Firebase relay 會呼叫這個）
    function mintTo(address to, uint256 amount) external onlyOwner {
        _mint(to, amount);
    }

    // 測試用：一次性 mint 給自己
    function initialMint(uint256 amount) external onlyOwner {
        _mint(msg.sender, amount);
    }
}
