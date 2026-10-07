// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

contract Nft721Mock {
    bool public immutable enumerableEnabled;
    mapping(uint256 => address) private owners;
    mapping(address => uint256) private balances;
    mapping(address => uint256[]) private ownedTokens;
    mapping(uint256 => uint256) private ownerTokenIndex;
    mapping(uint256 => string) private tokenUris;

    event Transfer(address indexed from, address indexed to, uint256 indexed tokenId);

    constructor(bool enableEnumerable) {
        enumerableEnabled = enableEnumerable;
    }

    function supportsInterface(bytes4 interfaceId) external view returns (bool) {
        if (interfaceId == 0x01ffc9a7) return true;
        if (interfaceId == 0x80ac58cd) return true;
        return enumerableEnabled && interfaceId == 0x780e9d63;
    }

    function ownerOf(uint256 tokenId) external view returns (address) {
        address tokenOwner = owners[tokenId];
        require(tokenOwner != address(0), "missing token");
        return tokenOwner;
    }

    function tokenURI(uint256 tokenId) external view returns (string memory) {
        require(owners[tokenId] != address(0), "missing token");
        if (bytes(tokenUris[tokenId]).length > 0) {
            return tokenUris[tokenId];
        }
        return "data:application/json,%7B%22name%22%3A%22Test%20NFT%22%7D";
    }

    // 仅供测试配置元数据 URI。
    function setTokenURI(uint256 tokenId, string calldata value) external {
        require(owners[tokenId] != address(0), "missing token");
        tokenUris[tokenId] = value;
    }

    function balanceOf(address owner) external view returns (uint256) {
        require(owner != address(0), "zero owner");
        return balances[owner];
    }

    function tokenOfOwnerByIndex(address owner, uint256 index) external view returns (uint256) {
        require(enumerableEnabled, "enumerable disabled");
        require(index < ownedTokens[owner].length, "index out of range");
        return ownedTokens[owner][index];
    }

    function mint(address to, uint256 tokenId) external {
        require(to != address(0), "zero recipient");
        require(owners[tokenId] == address(0), "already minted");
        owners[tokenId] = to;
        balances[to] += 1;
        _addOwnedToken(to, tokenId);
        emit Transfer(address(0), to, tokenId);
    }

    function transferFrom(address from, address to, uint256 tokenId) external {
        require(owners[tokenId] == from, "wrong owner");
        require(to != address(0), "zero recipient");
        owners[tokenId] = to;
        balances[from] -= 1;
        balances[to] += 1;
        _removeOwnedToken(from, tokenId);
        _addOwnedToken(to, tokenId);
        emit Transfer(from, to, tokenId);
    }

    function burn(uint256 tokenId) external {
        address tokenOwner = owners[tokenId];
        require(tokenOwner != address(0), "missing token");
        delete owners[tokenId];
        balances[tokenOwner] -= 1;
        _removeOwnedToken(tokenOwner, tokenId);
        emit Transfer(tokenOwner, address(0), tokenId);
    }

    function _addOwnedToken(address owner, uint256 tokenId) private {
        ownerTokenIndex[tokenId] = ownedTokens[owner].length;
        ownedTokens[owner].push(tokenId);
    }

    function _removeOwnedToken(address owner, uint256 tokenId) private {
        if (!enumerableEnabled) return;
        uint256 index = ownerTokenIndex[tokenId];
        uint256 lastIndex = ownedTokens[owner].length - 1;
        if (index != lastIndex) {
            uint256 movedToken = ownedTokens[owner][lastIndex];
            ownedTokens[owner][index] = movedToken;
            ownerTokenIndex[movedToken] = index;
        }
        ownedTokens[owner].pop();
        delete ownerTokenIndex[tokenId];
    }
}
