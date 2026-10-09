-- Fresh databases only. Select/create your database before executing this file.
-- Does not modify existing tables; do not use as an upgrade migration.
CREATE TABLE IF NOT EXISTS `user` (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  userAccount varchar(256) NOT NULL,
  userPassword varchar(512) NOT NULL,
  userName varchar(256),
  userAvatar varchar(1024),
  userProfile varchar(512),
  userRole varchar(256) NOT NULL DEFAULT 'user',
  editTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  createTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  isDelete tinyint NOT NULL DEFAULT 0,
  vipExpireTime datetime,
  vipCode varchar(128),
  vipNumber bigint,
  UNIQUE KEY uk_userAccount (userAccount),
  KEY idx_userName (userName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS space (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  spaceName varchar(128),
  spaceLevel int NOT NULL DEFAULT 0,
  maxSize bigint NOT NULL DEFAULT 0,
  maxCount bigint NOT NULL DEFAULT 0,
  totalSize bigint NOT NULL DEFAULT 0,
  totalCount bigint NOT NULL DEFAULT 0,
  userId bigint NOT NULL,
  createTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  editTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  isDelete tinyint NOT NULL DEFAULT 0,
  spaceType int NOT NULL DEFAULT 0,
  KEY idx_userId (userId),
  KEY idx_spaceName (spaceName),
  KEY idx_spaceLevel (spaceLevel),
  KEY idx_spaceType (spaceType)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS picture (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  url varchar(512) NOT NULL,
  name varchar(128) NOT NULL,
  introduction varchar(512),
  category varchar(64),
  tags varchar(512),
  picSize bigint,
  picWidth int,
  picHeight int,
  picScale double,
  picFormat varchar(32),
  userId bigint NOT NULL,
  createTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  editTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  isDelete tinyint NOT NULL DEFAULT 0,
  reviewStatus int NOT NULL DEFAULT 0,
  reviewMessage varchar(512),
  reviewerId bigint,
  reviewTime datetime,
  thumbnailUrl varchar(512),
  spaceId bigint,
  picColor varchar(16),
  KEY idx_name (name),
  KEY idx_category (category),
  KEY idx_userId (userId),
  KEY idx_reviewStatus (reviewStatus),
  KEY idx_spaceId (spaceId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS space_user (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  spaceId bigint NOT NULL,
  userId bigint NOT NULL,
  spaceRole varchar(128) NOT NULL DEFAULT 'viewer',
  createTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_spaceId_userId (spaceId, userId),
  KEY idx_spaceId (spaceId),
  KEY idx_userId (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
